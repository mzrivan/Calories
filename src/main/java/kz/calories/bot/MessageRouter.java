package kz.calories.bot;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import kz.calories.energy.ActivityRepository;
import kz.calories.meal.MealEntry;
import java.util.Optional;
import java.util.UUID;
import kz.calories.agent.AgentReply;
import kz.calories.agent.RecordRef;
import kz.calories.agent.AgentService;
import kz.calories.agent.ChatRepository;
import kz.calories.dish.DishFormat;
import kz.calories.dish.DishService;
import kz.calories.dish.DishVersion;
import kz.calories.energy.ActivityService;
import kz.calories.energy.BalanceFormatter;
import kz.calories.energy.DayBalance;
import kz.calories.meal.MealBatchService;
import kz.calories.meal.MealService;
import kz.calories.meal.SummaryFormatter;
import kz.calories.parse.IntentParser;
import kz.calories.parse.MealTextParser;
import kz.calories.user.AppUser;
import kz.calories.user.UserService;
import kz.calories.validation.Nutrition;
import kz.calories.validation.NutritionValidator;
import kz.calories.validation.ValidationResult;
import kz.calories.agent.AgentContext;
import kz.calories.dish.DraftPayload;
import kz.calories.parse.ProductLineParser;
import org.springframework.stereotype.Component;

/**
 * Маршрутизация без зависимости от Telegram API. Порядок, каждый шаг без LLM, пока не сработает:
 * команды → ключевые слова (вес, шаги, профиль, цель, итоги, баланс) → «продукт К/Б/Ж/У[/клетчатка]» → список «блюдо + вес» →
 * и только потом LLM-агент (рецепты, КБЖУ в тексте, правки, вопросы, всё нераспознанное).
 */
@Component
public class MessageRouter {

    public record CallbackResult(String toast, String newText, List<RecordRef> records) {
        public CallbackResult(String toast, String newText) {
            this(toast, newText, List.of());
        }
    }

    static final String HELP = """
            Я веду дневник питания и считаю расход калорий. Пишите обычным текстом:
            • «хлеб 30, молоко 100, мандарин 60» — запишу список (или «70 йогурт, 7 карамель»)
            • «вот рецепт борща: … вышло 3 кг» — сохраню блюдо (после вашего подтверждения)
            • «творог Простоквашино 5%: 121 ккал, Б 16, Ж 5, У 3» — сохраню продукт
            • «Творог Простоквашино 121/16/5/3» — продукт: ккал/белки/жиры/углеводы[/клетчатка] на 100 г
            • «не 400, а 300 г», «удали последнюю запись» — правки (можно попросить удалить блюдо или активность словами)
            • «цель 2000 ккал»

            Расход калорий:
            • один раз: «рост 178, возраст 35, пол м»
            • «вес 82,5», «шагов 9500» (можно «вчера шагов 8000»; данные за день можно обновлять)
            • «баланс» — съедено минус потрачено

            Удаление (запись лишь помечается неактивной и больше не учитывается):
            • перешлите мне моё сообщение и напишите «удали», «отмени» или правку («измени вес на 200 г»)
            • также можно ответить на моё сообщение командой или правкой
            • /today [дата] — записи дня с номерами и id
            • /del 3 [дата] — убрать запись №3 из списка дня (сначала еда, потом активности)
            • /delmeal 345 — убрать запись еды по id, /delact 7 — активность по id
            • /deldish 12 — деактивировать блюдо/продукт по id версии (id виден в /dishes)
            Дата: «вчера», 2026-03-01 или 01.03.

            Команды: /today — итоги дня, /balance — расход и баланс, /weight, /steps, /profile,
            /dishes [поиск] — сохранённые блюда, /help — эта справка.""";

    private final UserService users;
    private final AgentService agent;
    private final DishService dishes;
    private final MealService meals;
    private final MealBatchService batches;
    private final ActivityService activity;
    private final ChatRepository chat;
    private final NutritionValidator validator;

    public MessageRouter(UserService users, AgentService agent, DishService dishes, MealService meals,
                         MealBatchService batches, ActivityService activity, ChatRepository chat,
                         NutritionValidator validator) {
        this.users = users;
        this.agent = agent;
        this.dishes = dishes;
        this.meals = meals;
        this.batches = batches;
        this.activity = activity;
        this.chat = chat;
        this.validator = validator;
    }

    /** empty = сообщение игнорируется (чужой или неактивный пользователь). */
    public Optional<AgentReply> handleText(long telegramId, String firstName, String text) {
        Optional<AppUser> resolved = users.resolve(telegramId, firstName);
        if (resolved.isEmpty() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        AppUser user = resolved.get();
        String t = text.trim();
        if (t.startsWith("/")) {
            String[] parts = t.split("\\s+", 2);
            String cmd = parts[0].toLowerCase(Locale.ROOT).replaceAll("@.*$", "");
            String arg = parts.length > 1 ? parts[1].trim() : "";
            switch (cmd) {
                case "/start":
                    return Optional.of(AgentReply.text("Привет, " + user.name() + "!\n\n" + HELP));
                case "/help":
                    return Optional.of(AgentReply.text(HELP));
                case "/today":
                    return Optional.of(AgentReply.text(dayArg(user, arg, d -> dayText(user, d))));
                case "/del":
                    return Optional.of(AgentReply.text(logged(user, t, deleteByNumber(user, arg))));
                case "/delmeal":
                    return Optional.of(AgentReply.text(logged(user, t, deleteMeal(user, arg))));
                case "/delact":
                    return Optional.of(AgentReply.text(logged(user, t, deleteActivity(user, arg))));
                case "/deldish":
                    return Optional.of(AgentReply.text(logged(user, t, deleteDish(user, arg))));
                case "/balance":
                    return Optional.of(AgentReply.text(BalanceFormatter.block(activity.balance(user, meals.today(user)))));
                case "/dishes":
                    return Optional.of(AgentReply.text(dishesText(arg)));
                case "/weight":
                    return Optional.of(arg.isEmpty() ? AgentReply.text("Напишите вес: /weight 82,5") : process(user, "вес " + arg));
                case "/steps":
                    return Optional.of(arg.isEmpty() ? AgentReply.text("Напишите шаги: /steps 9500") : process(user, "шагов " + arg));
                case "/profile":
                    return Optional.of(arg.isEmpty() ? AgentReply.text(profileText(user)) : process(user, arg));
                default:
                    break; // неизвестная команда → разбираем как обычный текст
            }
        }
        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("справка") || low.equals("помощь")) {
            return Optional.of(AgentReply.text(HELP));
        }
        return Optional.of(process(user, t));
    }

    private AgentReply process(AppUser user, String text) {
        IntentParser.Intent in = IntentParser.parse(text);
        if (!in.any()) {
            return processFood(user, text);
        }
        String reply = applyIntent(user, in);
        chat.save(user.id(), true, text);
        chat.save(user.id(), false, reply);
        AgentReply head = AgentReply.text(reply);
        if (in.remainder().isBlank()) {
            return head;
        }
        return head.and(processFood(user, in.remainder()));
    }

    /** Список «блюдо + вес» → код; не разобралось → LLM-агент. */
    private AgentReply processFood(AppUser user, String text) {
        Optional<ProductLineParser.Product> product = ProductLineParser.parse(text);
        if (product.isPresent()) {
            return saveProductDraft(user, text, product.get());
        }
        MealTextParser.ParsedMessage pm = MealTextParser.parse(text);
        if (!pm.ok()) {
            return agent.handle(user, text);
        }
        chat.save(user.id(), true, text);
        AgentReply reply = batches.process(user, pm.items(), text, pm.mealType());
        chat.save(user.id(), false, reply.text());
        return reply;
    }

    /** «Название К/Б/Ж/У[/кл]» → черновик продукта (на 100 г) с кнопками подтверждения; в дневник не пишет. */
    private AgentReply saveProductDraft(AppUser user, String text, ProductLineParser.Product p) {
        Nutrition per100 = p.per100();
        ValidationResult vr = validator.validatePer100(per100);
        chat.save(user.id(), true, text);
        if (!vr.hard().isEmpty()) {
            String err = "Не сохранил «" + p.name() + "»: " + vr.message() + ".";
            chat.save(user.id(), false, err);
            return AgentReply.text(err);
        }
        boolean low = !vr.soft().isEmpty();
        DraftPayload payload = new DraftPayload(p.name(), DishVersion.Kind.PRODUCT, per100, null, null, text, null,
                low ? "LOW" : "HIGH", low ? "не прошло проверку: " + vr.message() : "значения на 100 г указал пользователь");
        long draftId = dishes.createDraft(user.id(), payload);
        String head = low ? "Значения выглядят подозрительно (" + vr.message() + "). Проверьте и подтвердите или пришлите исправленную строку."
                : "Проверьте и подтвердите.";
        AgentReply reply = new AgentReply(head, List.of(AgentContext.DraftCard.dish(draftId, DishFormat.card(payload))));
        chat.save(user.id(), false, head + "\n" + DishFormat.card(payload));
        return reply;
    }

    private String applyIntent(AppUser user, IntentParser.Intent in) {
        StringBuilder sb = new StringBuilder();
        AppUser current = user;
        LocalDate today = meals.today(user);
        try {
            if (in.goalKcal() != null) {
                users.setGoals(user.id(), new AppUser.Goals(in.goalKcal(), null, null, null, null));
                current = users.findById(user.id()).orElse(user);
                sb.append("Цель на день: ").append(SummaryFormatter.num(in.goalKcal())).append(" ккал.\n\n").append(meals.todayBlock(current));
            }
            if (in.hasProfile()) {
                saveProfile(user, in);
                current = users.findById(user.id()).orElse(user);
                activity.recompute(current, today);
                sb.append("Профиль: ").append(profileText(current));
            }
            if (in.weightKg() != null || in.steps() != null) {
                LocalDate day = in.yesterday() ? today.minusDays(1) : today;
                DayBalance b = activity.record(current, day, in.weightKg(), in.steps());
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("Сохранено");
                if (in.weightKg() != null) {
                    sb.append(": вес ").append(trim(in.weightKg())).append(" кг");
                }
                if (in.steps() != null) {
                    sb.append(in.weightKg() != null ? ", " : ": ").append(in.steps()).append(" шагов");
                }
                sb.append(in.yesterday() ? " (за вчера)" : "").append(".\n").append(BalanceFormatter.block(b));
            } else if (in.hasProfile() && current.profile().complete()) {
                sb.append("\n\n").append(BalanceFormatter.block(activity.balance(current, today)));
            }
            if (in.query() == IntentParser.Query.SUMMARY) {
                sb.append(todayText(current));
            } else if (in.query() == IntentParser.Query.BALANCE) {
                sb.append(BalanceFormatter.block(activity.balance(current, today)));
            }
        } catch (IllegalArgumentException e) {
            return "Не получилось сохранить: " + e.getMessage() + ".";
        }
        return sb.toString().trim();
    }

    private void saveProfile(AppUser user, IntentParser.Intent in) {
        if (in.heightCm() != null && (in.heightCm() < 50 || in.heightCm() > 260)) {
            throw new IllegalArgumentException("рост должен быть 50–260 см");
        }
        if (in.age() != null && (in.age() < 5 || in.age() > 120)) {
            throw new IllegalArgumentException("возраст должен быть 5–120 лет");
        }
        Integer birthYear = in.age() == null ? null : meals.today(user).getYear() - in.age();
        users.setProfile(user.id(), in.heightCm(), birthYear, in.sex());
    }

    public Optional<CallbackResult> handleCallback(long telegramId, String firstName, String data) {
        Optional<AppUser> resolved = users.resolve(telegramId, firstName);
        if (resolved.isEmpty() || data == null || !data.contains(":")) {
            return Optional.empty();
        }
        AppUser user = resolved.get();
        String[] p = data.split(":", 2);
        try {
            if ("undo".equals(p[0])) {
                int n = batches.undo(user, UUID.fromString(p[1]));
                if (n > 0) {
                    chat.save(user.id(), false, "[Пользователь отменил кнопкой последнюю запись списка (" + n + " поз.)]");
                }
                return Optional.of(new CallbackResult(n > 0 ? "Отменено" : "Уже отменено",
                        n > 0 ? "↩ Запись отменена.\n\n" + meals.todayBlock(user) : null));
            }
            long draftId = Long.parseLong(p[1]);
            switch (p[0]) {
                case "save": {
                    DishVersion v = dishes.confirmDraft(draftId, user.id());
                    String done = "✅ Сохранено: " + DishFormat.line(v);
                    chat.save(user.id(), false, "[Пользователь подтвердил кнопкой, сохранена версия] " + done);
                    return Optional.of(new CallbackResult("Сохранено", done, List.of(new RecordRef(RecordRef.Kind.DISH, v.id()))));
                }
                case "cancel": {
                    boolean ok = dishes.cancelDraft(draftId, user.id());
                    if (ok) {
                        chat.save(user.id(), false, "[Пользователь отменил черновик блюда #" + draftId + "]");
                    }
                    return Optional.of(new CallbackResult(ok ? "Отменено" : "Черновик уже обработан", ok ? "❌ Отменено" : null));
                }
                case "mconfirm": {
                    MealBatchService.Confirmed c = batches.confirm(user, draftId);
                    chat.save(user.id(), false, "[Пользователь подтвердил кнопкой] " + c.text());
                    return Optional.of(new CallbackResult("Записано", c.text(), c.records()));
                }
                case "mcancel": {
                    boolean ok = batches.cancel(user, draftId);
                    if (ok) {
                        chat.save(user.id(), false, "[Пользователь отменил список блюд]");
                    }
                    return Optional.of(new CallbackResult(ok ? "Отменено" : "Уже обработано", ok ? "❌ Отменено, ничего не записано." : null));
                }
                default:
                    return Optional.empty();
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of(new CallbackResult(e.getMessage(), null));
        }
    }

    private String todayText(AppUser user) {
        return dayText(user, meals.today(user));
    }

    /** Записи дня с номерами (еда, затем активности — сквозная нумерация для /del N) и id. */
    private String dayText(AppUser user, LocalDate day) {
        boolean isToday = day.equals(meals.today(user));
        List<MealEntry> entries = meals.entriesOfDay(user, day);
        List<ActivityRepository.Entry> acts = activity.entriesOfDay(user, day);
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty() && acts.isEmpty()) {
            sb.append(isToday ? "Сегодня записей пока нет." : day.format(DAY_FMT) + ": записей нет.").append("\n\n");
        } else {
            Map<Long, Integer> numbers = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) {
                numbers.put(entries.get(i).id(), i + 1);
            }
            sb.append(SummaryFormatter.entriesByType(entries, e -> String.format(Locale.ROOT, "%d. %s, %s г — %s (id %d)",
                    numbers.get(e.id()), e.name(), SummaryFormatter.num(e.grams()), SummaryFormatter.nutrition(e.nutrition()), e.id())));
            if (!acts.isEmpty()) {
                sb.append("\n🏃 Активности\n");
                for (int i = 0; i < acts.size(); i++) {
                    ActivityRepository.Entry a = acts.get(i);
                    sb.append(String.format(Locale.ROOT, "%d. %s, %s мин — %s ккал (акт. id %d)%n", entries.size() + i + 1,
                            a.name(), SummaryFormatter.num(a.minutes()), SummaryFormatter.num(a.kcal()), a.id()));
                }
            }
            sb.append('\n');
        }
        sb.append(meals.dayBlock(user, day));
        DayBalance b = activity.balance(user, day);
        if (b.known()) { // расход показываем, только если данные для него есть
            sb.append("\n\n").append(BalanceFormatter.block(b));
        }
        return sb.toString();
    }

    // ---------- удаление (мягкое: active = false) ----------

    private static final java.time.format.DateTimeFormatter DAY_FMT = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String DEL_USAGE = "Формат: /del <номер> [дата] — номер из /today. Или /delmeal <id>, /delact <id>, /deldish <id>.";
    private static final String BAD_DATE = "Не поняла дату «%s». Пример: вчера, 2026-03-01, 01.03.";

    /** Сохраняет команду и ответ в историю чата, чтобы LLM знала об удалении. */
    private String logged(AppUser user, String command, String reply) {
        chat.save(user.id(), true, command);
        chat.save(user.id(), false, reply);
        return reply;
    }

    private String dayArg(AppUser user, String arg, Function<LocalDate, String> render) {
        LocalDate today = meals.today(user);
        if (arg.isBlank()) {
            return render.apply(today);
        }
        return parseDay(arg, today).map(render).orElse(BAD_DATE.formatted(arg));
    }

    public static Optional<LocalDate> parseDay(String s, LocalDate today) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        try {
            switch (t) {
                case "сегодня":
                    return Optional.of(today);
                case "вчера":
                    return Optional.of(today.minusDays(1));
                case "позавчера":
                    return Optional.of(today.minusDays(2));
                default:
                    break;
            }
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return Optional.of(LocalDate.parse(t));
            }
            if (t.matches("\\d{1,2}\\.\\d{1,2}(\\.\\d{4})?")) {
                String[] p = t.split("\\.");
                int year = p.length > 2 ? Integer.parseInt(p[2]) : today.getYear();
                LocalDate d = LocalDate.of(year, Integer.parseInt(p[1]), Integer.parseInt(p[0]));
                return Optional.of(p.length > 2 || !d.isAfter(today) ? d : d.minusYears(1));
            }
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String deleteByNumber(AppUser user, String arg) {
        String[] a = arg.trim().split("\\s+");
        if (arg.isBlank() || !a[0].matches("\\d{1,6}")) {
            return DEL_USAGE;
        }
        LocalDate day = a.length > 1 ? parseDay(a[1], meals.today(user)).orElse(null) : meals.today(user);
        if (day == null) {
            return BAD_DATE.formatted(a[1]);
        }
        int n = Integer.parseInt(a[0]);
        List<MealEntry> entries = meals.entriesOfDay(user, day);
        List<ActivityRepository.Entry> acts = activity.entriesOfDay(user, day);
        if (n < 1 || n > entries.size() + acts.size()) {
            return "За " + day.format(DAY_FMT) + " нет записи №" + n + ". Список: /today" + (a.length > 1 ? " " + a[1] : "") + ".";
        }
        String removed;
        if (n <= entries.size()) {
            Optional<MealEntry> e = meals.deactivate(user, entries.get(n - 1).id());
            if (e.isEmpty()) {
                return "Запись уже удалена. Список: /today.";
            }
            removed = e.get().name() + ", " + SummaryFormatter.num(e.get().grams()) + " г";
        } else {
            Optional<ActivityRepository.Entry> e = activity.deactivateActivity(user, acts.get(n - entries.size() - 1).id());
            if (e.isEmpty()) {
                return "Активность уже удалена. Список: /today.";
            }
            removed = "активность «" + e.get().name() + "»";
        }
        return "🗑 Убрано: " + removed + ".\nНомера в списке сдвинулись — актуальные ниже.\n\n" + dayText(user, day);
    }

    private String deleteMeal(AppUser user, String arg) {
        Optional<Long> id = parseId(arg);
        if (id.isEmpty()) {
            return "Формат: /delmeal <id записи> (id виден в /today).";
        }
        return meals.deactivate(user, id.get())
                .map(e -> "🗑 Убрано: " + e.name() + ", " + SummaryFormatter.num(e.grams()) + " г.\n\n"
                        + meals.dayBlock(user, e.eatenAt().atZoneSameInstant(user.zone()).toLocalDate()))
                .orElse("Активная запись еды с id " + id.get() + " у вас не найдена.");
    }

    private String deleteActivity(AppUser user, String arg) {
        Optional<Long> id = parseId(arg);
        if (id.isEmpty()) {
            return "Формат: /delact <id активности> (id виден в /today).";
        }
        return activity.deactivateActivity(user, id.get())
                .map(e -> "🗑 Активность убрана: " + e.name() + ".\n\n" + BalanceFormatter.block(activity.balance(user, e.day())))
                .orElse("Активная активность с id " + id.get() + " у вас не найдена.");
    }

    private String deleteDish(AppUser user, String arg) {
        Optional<Long> id = parseId(arg);
        if (id.isEmpty()) {
            return "Формат: /deldish <id версии> (id виден в /dishes, например #12).";
        }
        Optional<DishVersion> v = dishes.findActiveVersion(id.get());
        if (v.isEmpty()) {
            return "Активной версии блюда с id " + id.get() + " нет.";
        }
        if (v.get().createdByUserId() == null) {
            return "«" + v.get().name() + "» — из системного справочника, его нельзя деактивировать.";
        }
        dishes.deactivate(id.get());
        return "🗑 Блюдо деактивировано: " + v.get().name() + " (#" + id.get() + ")."
                + dishes.latestActive(v.get().name()).map(n -> "\nТеперь актуальна: " + DishFormat.line(n))
                        .orElse("\nДругих активных версий нет.")
                + "\nУже сделанные записи в дневнике не меняются — уберите их через /del.";
    }

    private static Optional<Long> parseId(String arg) {
        String t = arg.trim().replaceFirst("^#", "");
        return t.matches("\\d{1,18}") ? Optional.of(Long.parseLong(t)) : Optional.empty();
    }

    private static String profileText(AppUser user) {
        AppUser.Profile p = user.profile();
        if (p.heightCm() == null && p.birthYear() == null && p.sex() == null) {
            return "не задан. Напишите: «рост 178, возраст 35, пол м».";
        }
        return (p.heightCm() == null ? "рост ?" : "рост " + trim(p.heightCm()) + " см")
                + ", " + (p.birthYear() == null ? "возраст ?" : "возраст " + (LocalDate.now(user.zone()).getYear() - p.birthYear()))
                + ", " + (p.sex() == null ? "пол ?" : "M".equals(p.sex()) ? "пол м" : "пол ж") + ".";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private String dishesText(String filter) {
        List<DishVersion> list = dishes.listLatest(filter, 50);
        if (list.isEmpty()) {
            return filter.isBlank() ? "Сохранённых блюд пока нет." : "По запросу «" + filter + "» ничего не найдено.";
        }
        StringBuilder sb = new StringBuilder("Сохранённые блюда и продукты (актуальные версии):\n");
        list.forEach(v -> sb.append("• ").append(DishFormat.line(v)).append('\n'));
        return sb.toString();
    }
}
