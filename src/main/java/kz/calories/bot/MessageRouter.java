package kz.calories.bot;

import java.util.List;
import java.util.Optional;
import kz.calories.agent.AgentReply;
import kz.calories.agent.AgentService;
import kz.calories.agent.ChatRepository;
import kz.calories.dish.DishFormat;
import kz.calories.dish.DishService;
import kz.calories.dish.DishVersion;
import kz.calories.meal.MealService;
import kz.calories.user.AppUser;
import kz.calories.user.UserService;
import org.springframework.stereotype.Component;

/** Маршрутизация без зависимости от Telegram API: команды и обычный текст (всё остальное уходит агенту). */
@Component
public class MessageRouter {

    public record CallbackResult(String toast, String newText) {}

    static final String HELP = """
            Я веду дневник питания. Просто пишите обычным текстом:
            • «съел 300 г борща и 150 г творога»
            • «вот рецепт борща: … вышло 3 кг» — сохраню блюдо (после вашего подтверждения)
            • «творог Простоквашино 5%: 121 ккал, Б 16, Ж 5, У 3» — сохраню продукт
            • «не 400, а 300 г», «удали последнюю запись» — правки
            • «цель 2000 ккал, белка 120 г»

            Команды: /today — итоги дня, /dishes [поиск] — сохранённые блюда, /help — эта справка.""";

    private final UserService users;
    private final AgentService agent;
    private final DishService dishes;
    private final MealService meals;
    private final ChatRepository chat;

    public MessageRouter(UserService users, AgentService agent, DishService dishes, MealService meals, ChatRepository chat) {
        this.users = users;
        this.agent = agent;
        this.dishes = dishes;
        this.meals = meals;
        this.chat = chat;
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
            String cmd = parts[0].toLowerCase().replaceAll("@.*$", "");
            String arg = parts.length > 1 ? parts[1] : "";
            switch (cmd) {
                case "/start":
                    return Optional.of(AgentReply.text("Привет, " + user.name() + "!\n\n" + HELP));
                case "/help":
                    return Optional.of(AgentReply.text(HELP));
                case "/today":
                    return Optional.of(AgentReply.text(todayText(user)));
                case "/dishes":
                    return Optional.of(AgentReply.text(dishesText(arg)));
                default:
                    break; // неизвестная команда → пусть разберётся агент как с обычным текстом
            }
        }
        return Optional.of(agent.handle(user, t));
    }

    public Optional<CallbackResult> handleCallback(long telegramId, String firstName, String data) {
        Optional<AppUser> resolved = users.resolve(telegramId, firstName);
        if (resolved.isEmpty() || data == null || !data.contains(":")) {
            return Optional.empty();
        }
        AppUser user = resolved.get();
        String[] p = data.split(":", 2);
        long draftId;
        try {
            draftId = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        try {
            if ("save".equals(p[0])) {
                DishVersion v = dishes.confirmDraft(draftId, user.id());
                String done = "✅ Сохранено: " + DishFormat.line(v);
                chat.save(user.id(), false, "[Пользователь подтвердил кнопкой, сохранена версия] " + done);
                return Optional.of(new CallbackResult("Сохранено", done));
            }
            if ("cancel".equals(p[0])) {
                boolean ok = dishes.cancelDraft(draftId, user.id());
                if (ok) {
                    chat.save(user.id(), false, "[Пользователь отменил черновик блюда #" + draftId + "]");
                }
                return Optional.of(new CallbackResult(ok ? "Отменено" : "Черновик уже обработан", ok ? "❌ Отменено" : null));
            }
        } catch (RuntimeException e) {
            return Optional.of(new CallbackResult(e.getMessage(), null));
        }
        return Optional.empty();
    }

    private String todayText(AppUser user) {
        var entries = meals.entriesOfDay(user, meals.today(user));
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) {
            sb.append("Сегодня записей пока нет.\n\n");
        } else {
            for (var e : entries) {
                sb.append(String.format("• %s, %s г — %s%n", e.name(), (long) Math.round(e.grams()),
                        kz.calories.meal.SummaryFormatter.nutrition(e.nutrition())));
            }
            sb.append('\n');
        }
        return sb.append(meals.todayBlock(user)).toString();
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
