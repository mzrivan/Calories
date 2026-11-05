package kz.calories.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import kz.calories.config.AppProperties;
import kz.calories.dish.DishFormat;
import kz.calories.dish.DishMatcher;
import kz.calories.dish.DishRepository;
import kz.calories.energy.ActivityService;
import kz.calories.dish.DishService;
import kz.calories.dish.DishVersion;
import kz.calories.dish.DraftPayload;
import kz.calories.meal.DayTotals;
import kz.calories.meal.MealEntry;
import kz.calories.meal.MealService;
import kz.calories.meal.SummaryFormatter;
import kz.calories.user.AppUser;
import kz.calories.user.UserService;
import kz.calories.validation.Nutrition;
import kz.calories.validation.NutritionValidator;
import kz.calories.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Инструменты LLM-агента. Экземпляр создаётся на каждый запрос с уже привязанным пользователем:
 * user_id берётся из контекста Telegram-сообщения, а не из аргументов LLM.
 * Инструментов удаления нет — только deactivate_*. Инструментов для app_user нет вообще (кроме целей).
 */
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final AppUser user;
    private final AgentContext ctx;
    private final DishService dishes;
    private final DishMatcher matcher;
    private final ActivityService activity;
    private final MealService meals;
    private final UserService users;
    private final NutritionValidator validator;
    private final AppProperties props;

    public AgentTools(AppUser user, AgentContext ctx, DishService dishes, DishMatcher matcher, ActivityService activity,
                      MealService meals, UserService users, NutritionValidator validator, AppProperties props) {
        this.matcher = matcher;
        this.activity = activity;
        this.user = user;
        this.ctx = ctx;
        this.dishes = dishes;
        this.meals = meals;
        this.users = users;
        this.validator = validator;
        this.props = props;
    }

    // ------------------------------------------------------------------ поиск

    @Tool(name = "find_dish", value = "Ищет сохранённые блюда и продукты по названию (точное совпадение, алиас, похожее написание). "
            + "Возвращает кандидатов с id версии, видом, датой версии и оценкой схожести. Решение, какой подходит, принимаешь ты; "
            + "при сомнении спроси пользователя.")
    public String findDish(@P("название блюда или продукта") String query) {
        return run(() -> {
            List<DishRepository.Scored> found = matcher.candidates(query);
            if (found.isEmpty()) {
                return "Ничего похожего в памяти нет. Предложи пользователю добавить блюдо или запиши приблизительную оценку через log_estimate.";
            }
            StringBuilder sb = new StringBuilder("Кандидаты (самая свежая активная версия каждого блюда):\n");
            for (DishRepository.Scored c : found) {
                sb.append(String.format(Locale.ROOT, "- %s [схожесть %.2f, %s]%n", DishFormat.line(c.version()), c.score(), c.matchType()));
            }
            return sb.toString();
        });
    }

    @Tool(name = "get_dish_versions", value = "История версий блюда: все версии (активные и деактивированные) от новой к старой. Укажи id любой версии или название.")
    public String getDishVersions(@P(value = "id любой версии блюда", required = false) Long dishVersionId,
                                  @P(value = "название блюда", required = false) String name) {
        return run(() -> {
            List<DishVersion> versions = dishes.versions(dishVersionId, name);
            if (versions.isEmpty()) {
                return "Версий не найдено.";
            }
            StringBuilder sb = new StringBuilder();
            for (DishVersion v : versions) {
                sb.append("- ").append(DishFormat.line(v)).append(v.active() ? "" : " [деактивирована]").append('\n');
            }
            return sb.toString();
        });
    }

    // ------------------------------------------------------------------ блюда

    @Tool(name = "save_dish", value = "Создаёт ЧЕРНОВИК новой версии рецепта или магазинного продукта; пользователь подтверждает кнопкой. "
            + "RECIPE: kcal/proteinG/fatG/carbsG/fiberG — значения ВСЕГО приготовленного блюда целиком (учти метод, потери, впитывание масла); "
            + "cookedWeightG (вес готового) обязателен — если пользователь его не назвал, СНАЧАЛА спроси, не оценивай молча. "
            + "Значения на 100 г посчитает код. PRODUCT: значения указываются на 100 г. carbsG включает клетчатку. "
            + "Клетчатка необязательна: если данных нет, не передавай fiberG.")
    public String saveDish(
            @P("название блюда/продукта") String name,
            @P("RECIPE или PRODUCT") String kind,
            @P("для RECIPE: итог всего блюда; для PRODUCT: на 100 г — калории, ккал") Double kcal,
            @P("белки, г (см. выше про RECIPE/PRODUCT)") Double proteinG,
            @P("жиры, г") Double fatG,
            @P("углеводы, г (включая клетчатку)") Double carbsG,
            @P(value = "клетчатка, г; пропусти, если неизвестна", required = false) Double fiberG,
            @P(value = "RECIPE: вес готового блюда, г", required = false) Double cookedWeightG,
            @P(value = "RECIPE: сумма сырых ингредиентов, г (для проверки правдоподобия)", required = false) Double rawWeightG,
            @P(value = "метод приготовления", required = false) String method,
            @P(value = "исходное описание рецепта/продукта от пользователя", required = false) String sourceText,
            @P(value = "LOW, MEDIUM или HIGH", required = false) String confidence,
            @P(value = "допущения расчёта", required = false) String assumptions) {
        return run(() -> {
            DishVersion.Kind k = parseKind(kind);
            if (name == null || name.isBlank()) {
                return "Ошибка: название обязательно.";
            }
            if (kcal == null || proteinG == null || fatG == null || carbsG == null) {
                return "Ошибка: kcal, proteinG, fatG, carbsG обязательны.";
            }
            Nutrition given = new Nutrition(kcal, proteinG, fatG, carbsG, fiberG);
            ValidationResult vr = ValidationResult.empty();
            Nutrition per100;
            Double totalKcal = null;
            Double cooked = null;
            if (k == DishVersion.Kind.RECIPE) {
                ValidationResult w = validator.validateRecipeWeights(cookedWeightG, rawWeightG);
                vr.hard().addAll(w.hard());
                vr.soft().addAll(w.soft());
                if (!w.hard().isEmpty()) {
                    return "Ошибка валидации: " + vr.message();
                }
                cooked = cookedWeightG;
                totalKcal = kcal;
                per100 = given.per100(cooked).rounded();
            } else {
                per100 = given.rounded();
            }
            ValidationResult nr = validator.validatePer100(per100);
            vr.hard().addAll(nr.hard());
            vr.soft().addAll(nr.soft());
            Gate gate = gate("save_dish", vr);
            if (gate.error() != null) {
                return gate.error();
            }
            String conf = gate.low() ? "LOW" : normConfidence(confidence);
            String assum = withWarnings(assumptions, gate, vr);
            DraftPayload payload = new DraftPayload(name.trim(), k, per100, cooked, totalKcal, sourceText, method, conf, assum);
            long draftId = dishes.createDraft(user.id(), payload);
            ctx.markWrote();
            ctx.cards().add(AgentContext.DraftCard.dish(draftId, DishFormat.card(payload)));
            return "Черновик #" + draftId + " создан; пользователю будет показана карточка с кнопками «Сохранить»/«Отмена». "
                    + "Цифры не повторяй, коротко попроси подтвердить кнопкой или прислать правку текстом."
                    + (gate.low() ? " Валидация не прошла полностью: уверенность помечена как низкая, предложи поправить вручную." : "");
        });
    }

    @Tool(name = "deactivate_dish", value = "Мягко деактивирует версию блюда по id версии (физически не удаляет). Актуальной становится предыдущая активная версия.")
    public String deactivateDish(@P("id версии блюда") Long dishVersionId) {
        return run(() -> {
            Optional<DishVersion> v = dishes.deactivate(dishVersionId);
            if (v.isEmpty()) {
                return "Активная версия с таким id не найдена.";
            }
            ctx.markWrote();
            Optional<DishVersion> now = dishes.latestActive(v.get().name());
            return "Версия #" + dishVersionId + " «" + v.get().name() + "» деактивирована. "
                    + now.map(n -> "Теперь актуальна: " + DishFormat.line(n)).orElse("Других активных версий этого блюда нет.");
        });
    }

    // ------------------------------------------------------------------ дневник

    @Tool(name = "log_meal", value = "Записывает приём пищи по сохранённой версии блюда: код сам масштабирует значения на граммы. "
            + "Используй dishVersionId из find_dish.")
    public String logMeal(
            @P("id версии блюда из find_dish") Long dishVersionId,
            @P("вес порции, г") Double grams,
            @P(value = "когда съедено: ISO-8601 в локальном времени пользователя, например 2025-03-01T13:30; пусто = сейчас", required = false) String eatenAt) {
        return run(() -> {
            if (dishVersionId == null || grams == null) {
                return "Ошибка: dishVersionId и grams обязательны.";
            }
            ValidationResult g = validator.validateGrams(grams);
            if (!g.ok()) {
                return "Ошибка валидации: " + g.message();
            }
            Optional<DishVersion> v = dishes.findActiveVersion(dishVersionId);
            if (v.isEmpty()) {
                return "Ошибка: активной версии с id " + dishVersionId + " нет. Вызови find_dish заново.";
            }
            MealEntry e = meals.logMeal(user, v.get(), grams, parseTime(eatenAt));
            ctx.markDayChanged();
            ctx.added(e.nutrition());
            return "Записано: " + describe(e);
        });
    }

    @Tool(name = "log_estimate", value = "Записывает приём пищи по ОЦЕНКЕ (значения на 100 г), когда блюда нет в памяти. В память блюд не попадает и помечается как приблизительная. "
            + "Клетчатку (fiberPer100g) передавай, только если оцениваешь её; carbsPer100g включает клетчатку.")
    public String logEstimate(
            @P("название съеденного") String name,
            @P("вес порции, г (оцени, если не указан, и отрази в допущениях)") Double grams,
            @P("ккал на 100 г") Double kcalPer100g,
            @P("белки на 100 г") Double proteinPer100g,
            @P("жиры на 100 г") Double fatPer100g,
            @P("углеводы на 100 г (включая клетчатку)") Double carbsPer100g,
            @P(value = "клетчатка на 100 г", required = false) Double fiberPer100g,
            @P(value = "LOW, MEDIUM или HIGH", required = false) String confidence,
            @P(value = "допущения оценки", required = false) String assumptions,
            @P(value = "когда съедено, ISO-8601 локальное время; пусто = сейчас", required = false) String eatenAt) {
        return run(() -> {
            if (name == null || name.isBlank() || grams == null || kcalPer100g == null || proteinPer100g == null
                    || fatPer100g == null || carbsPer100g == null) {
                return "Ошибка: name, grams, kcalPer100g, proteinPer100g, fatPer100g, carbsPer100g обязательны.";
            }
            ValidationResult vr = ValidationResult.empty();
            ValidationResult g = validator.validateGrams(grams);
            vr.hard().addAll(g.hard());
            Nutrition per100 = new Nutrition(kcalPer100g, proteinPer100g, fatPer100g, carbsPer100g, fiberPer100g);
            ValidationResult nr = validator.validatePer100(per100);
            vr.hard().addAll(nr.hard());
            vr.soft().addAll(nr.soft());
            Gate gate = gate("log_estimate", vr);
            if (gate.error() != null) {
                return gate.error();
            }
            String conf = gate.low() ? "LOW" : normConfidence(confidence);
            MealEntry e = meals.logEstimate(user, name.trim(), grams, per100, parseTime(eatenAt), conf,
                    withWarnings(assumptions, gate, vr));
            ctx.markDayChanged();
            ctx.added(e.nutrition());
            return "Записано по оценке: " + describe(e);
        });
    }

    @Tool(name = "update_meal_entry", value = "Правит СВОЮ запись дневника: граммы (значения пересчитываются пропорционально), время и/или итоговые значения порции. "
            + "Чтобы найти id, используй get_day_summary. Не переданные поля не меняются.")
    public String updateMealEntry(
            @P("id записи") Long entryId,
            @P(value = "новый вес, г", required = false) Double grams,
            @P(value = "новое время ISO-8601 локальное", required = false) String eatenAt,
            @P(value = "новые ккал порции целиком", required = false) Double kcal,
            @P(value = "новые белки порции, г", required = false) Double proteinG,
            @P(value = "новые жиры порции, г", required = false) Double fatG,
            @P(value = "новые углеводы порции, г", required = false) Double carbsG,
            @P(value = "новая клетчатка порции, г", required = false) Double fiberG) {
        return run(() -> {
            Optional<MealEntry> found = entryId == null ? Optional.empty() : meals.find(user, entryId);
            if (found.isEmpty()) {
                return "Активная запись с таким id у этого пользователя не найдена.";
            }
            MealEntry old = found.get();
            double newGrams = grams == null ? old.grams() : grams;
            ValidationResult vr = validator.validateGrams(newGrams);
            if (!vr.ok()) {
                return "Ошибка валидации: " + vr.message();
            }
            Nutrition n = old.nutrition().scale(newGrams / old.grams());
            n = new Nutrition(kcal != null ? kcal : n.kcal(), proteinG != null ? proteinG : n.protein(),
                    fatG != null ? fatG : n.fat(), carbsG != null ? carbsG : n.carbs(), fiberG != null ? fiberG : n.fiber());
            boolean manualValues = kcal != null || proteinG != null || fatG != null || carbsG != null || fiberG != null;
            if (manualValues) {
                ValidationResult tr = validator.validateTotal(n);
                Gate gate = gate("update_meal_entry", tr);
                if (gate.error() != null) {
                    return gate.error();
                }
            }
            OffsetDateTime at = eatenAt == null || eatenAt.isBlank() ? old.eatenAt() : parseTime(eatenAt);
            if (!meals.update(user, old.id(), at, newGrams, n)) {
                return "Не удалось обновить запись.";
            }
            ctx.markDayChanged();
            return "Обновлено: " + describe(meals.find(user, old.id()).orElseThrow());
        });
    }

    @Tool(name = "deactivate_meal_entry", value = "Мягко деактивирует (убирает из итогов) СВОЮ запись дневника по id. Физически не удаляет.")
    public String deactivateMealEntry(@P("id записи") Long entryId) {
        return run(() -> {
            Optional<MealEntry> e = entryId == null ? Optional.empty() : meals.deactivate(user, entryId);
            if (e.isEmpty()) {
                return "Активная запись с таким id у этого пользователя не найдена.";
            }
            ctx.markDayChanged();
            return "Запись деактивирована: " + e.get().name() + " " + fmtG(e.get().grams()) + " г.";
        });
    }

    @Tool(name = "get_day_summary", value = "Записи и итоги дня этого пользователя (с id записей). Дата в формате YYYY-MM-DD, пусто = сегодня. "
            + "Цифры в ответе бери только отсюда, ничего не выдумывай.")
    public String getDaySummary(@P(value = "дата YYYY-MM-DD", required = false) String date) {
        return run(() -> {
            LocalDate day = date == null || date.isBlank() ? meals.today(user) : parseDate(date);
            List<MealEntry> entries = meals.entriesOfDay(user, day);
            DayTotals totals = meals.totalsOfDay(user, day);
            StringBuilder sb = new StringBuilder("День ").append(day).append(":\n");
            if (entries.isEmpty()) {
                sb.append("записей нет.\n");
            }
            for (MealEntry e : entries) {
                sb.append("- ").append(describe(e)).append('\n');
            }
            sb.append("Итого: ").append(SummaryFormatter.nutrition(totals.nutrition()));
            if (day.equals(meals.today(user))) {
                ctx.showTotals();
                sb.append("\n(блок «Итого за сегодня» добавится к ответу автоматически, цифры итога не пересказывай)");
            }
            return sb.toString();
        });
    }

    @Tool(name = "set_goal", value = "Задаёт дневные цели пользователя. Переданные значения заменяют прежние, непереданные остаются.")
    public String setGoal(
            @P(value = "цель, ккал", required = false) Double kcal,
            @P(value = "цель по белкам, г", required = false) Double proteinG,
            @P(value = "цель по жирам, г", required = false) Double fatG,
            @P(value = "цель по углеводам, г", required = false) Double carbsG,
            @P(value = "цель по клетчатке, г", required = false) Double fiberG) {
        return run(() -> {
            for (Double v : new Double[]{kcal, proteinG, fatG, carbsG, fiberG}) {
                if (v != null && (v < 0 || v > 20000)) {
                    return "Ошибка: цель должна быть в диапазоне 0–20000.";
                }
            }
            users.setGoals(user.id(), new AppUser.Goals(kcal, proteinG, fatG, carbsG, fiberG));
            ctx.showTotals();
            return "Цели обновлены.";
        });
    }

    // ------------------------------------------------------------------ вес, шаги, профиль

    @Tool(name = "log_activity", value = "Записывает произвольную физическую активность (уборка, зал, плавание, прогулка с собакой и т.п.) "
            + "и её расход. Предпочтительно передай met — коэффициент интенсивности по Compendium of Physical Activities "
            + "(напр. уборка 3.0–3.5, силовая средней интенсивности 5.0, бег 8 км/ч ≈ 8.3, плавание умеренное 5.8); код сам посчитает "
            + "чистый расход = (met−1) × вес × часы по весу пользователя. Если пользователь сам назвал сожжённые ккал "
            + "(например с часов) — передай kcal, это ЧИСТЫЙ расход сверх основного обмена. "
            + "НЕ записывай обычную ходьбу, если за день учитываются шаги (двойной счёт). Можно вызывать несколько раз за ход — по одной активности.")
    public String logActivity(@P("название активности по-русски, коротко") String name,
                              @P("длительность, минут") Double minutes,
                              @P(value = "MET активности, 1–20", required = false) Double met,
                              @P(value = "чистый расход, ккал — только если назвал пользователь", required = false) Double kcal,
                              @P(value = "LOW, MEDIUM или HIGH", required = false) String confidence,
                              @P(value = "допущения оценки (интенсивность, источник MET)", required = false) String assumptions,
                              @P(value = "дата YYYY-MM-DD, пусто = сегодня", required = false) String date) {
        return run(() -> {
            if (minutes == null) {
                return "Ошибка: нужна длительность в минутах (если не названа — уточни у пользователя).";
            }
            LocalDate day = date == null || date.isBlank() ? meals.today(user) : parseDate(date);
            var e = activity.logActivity(user, day, name, minutes, met, kcal, normConfidence(confidence),
                    assumptions == null || assumptions.isBlank() ? null : assumptions.trim());
            ctx.markWrote();
            ctx.showBalance(day);
            return String.format(Locale.ROOT, "Записано: id=%d «%s», %s мин, %s ккал%s. Блок «Расход и баланс» бот добавит сам, цифры не пересказывай.",
                    e.id(), e.name(), SummaryFormatter.num(e.minutes()), SummaryFormatter.num(e.kcal()),
                    e.met() == null ? "" : " (MET " + SummaryFormatter.num(e.met()) + ")");
        });
    }

    @Tool(name = "deactivate_activity", value = "Мягко убирает СВОЮ активность по id (id виден в блоке «Расход и баланс» и в ответе log_activity).")
    public String deactivateActivity(@P("id активности") Long id) {
        return run(() -> {
            if (id == null) {
                return "Ошибка: нужен id активности.";
            }
            var e = activity.deactivateActivity(user, id);
            if (e.isEmpty()) {
                return "Ошибка: активность с таким id не найдена.";
            }
            ctx.markWrote();
            ctx.showBalance(e.get().day());
            return "Активность убрана: " + e.get().name() + ".";
        });
    }

    @Tool(name = "record_activity", value = "Записывает вес (кг) и/или шаги за день и пересчитывает расход калорий (формулы в коде). "
            + "Шаги ЗАМЕНЯЮТ прежнее значение за день, а не прибавляются: передавай ИТОГОВОЕ число шагов за день на сейчас "
            + "(если пользователь пишет «итого 10000» — 10000; если пишет «ещё 3000» — прежнее значение + 3000, "
            + "прежнее возьми из блока расхода или спроси). Дата YYYY-MM-DD, пусто = сегодня.")
    public String recordActivity(@P(value = "вес, кг", required = false) Double weightKg,
                                 @P(value = "ИТОГО шагов за день (заменяет прежнее значение)", required = false) Integer steps,
                                 @P(value = "дата YYYY-MM-DD", required = false) String date) {
        return run(() -> {
            if (weightKg == null && steps == null) {
                return "Ошибка: нужен вес или шаги.";
            }
            LocalDate day = date == null || date.isBlank() ? meals.today(user) : parseDate(date);
            activity.record(user, day, weightKg, steps);
            ctx.markWrote();
            ctx.showBalance(day);
            return "Сохранено. Блок «Расход и баланс» бот добавит к ответу сам, цифры не пересказывай.";
        });
    }

    @Tool(name = "set_profile", value = "Задаёт рост (см), возраст (полных лет) и пол (M/F) для расчёта расхода калорий. Непереданные поля не меняются.")
    public String setProfile(@P(value = "рост, см", required = false) Double heightCm,
                             @P(value = "возраст, лет", required = false) Integer age,
                             @P(value = "M или F", required = false) String sex) {
        return run(() -> {
            String s = sex == null ? null : sex.trim().toUpperCase(Locale.ROOT);
            if (s != null && !s.equals("M") && !s.equals("F")) {
                return "Ошибка: sex должен быть M или F.";
            }
            if (heightCm != null && (heightCm < 50 || heightCm > 260)) {
                return "Ошибка: рост должен быть 50–260 см.";
            }
            if (age != null && (age < 5 || age > 120)) {
                return "Ошибка: возраст должен быть 5–120 лет.";
            }
            Integer birthYear = age == null ? null : meals.today(user).getYear() - age;
            users.setProfile(user.id(), heightCm, birthYear, s);
            ctx.markWrote();
            return "Профиль обновлён.";
        });
    }

    // ------------------------------------------------------------------ служебное

    private record Gate(String error, boolean low) {}

    /**
     * Отказ с текстом ошибки (LLM исправит вызов сама); после исчерпания попыток «мягкие» ошибки принимаются
     * с низкой уверенностью, «жёсткие» — никогда.
     */
    private Gate gate(String tool, ValidationResult vr) {
        if (vr.ok()) {
            return new Gate(null, false);
        }
        if (!vr.hard().isEmpty()) {
            return new Gate("Ошибка валидации: " + vr.message() + ". Исправь аргументы и вызови инструмент снова либо уточни у пользователя.", false);
        }
        int attempt = ctx.failure(tool);
        if (attempt > props.agent().maxValidationRetries()) {
            return new Gate(null, true);
        }
        return new Gate("Ошибка валидации: " + vr.message() + ". Пересчитай значения и вызови инструмент снова.", false);
    }

    private static String withWarnings(String assumptions, Gate gate, ValidationResult vr) {
        String base = assumptions == null ? "" : assumptions.trim();
        if (!gate.low()) {
            return base;
        }
        String warn = "не прошло проверку: " + vr.message();
        return base.isEmpty() ? warn : base + "; " + warn;
    }

    private String run(Supplier<String> body) {
        int n = ctx.nextToolCall();
        if (n > props.llm().maxToolCalls()) {
            return "Лимит вызовов инструментов за один ход исчерпан. Ответь пользователю по уже сделанному.";
        }
        try {
            return body.get();
        } catch (IllegalArgumentException e) {
            return "Ошибка: " + e.getMessage();
        } catch (RuntimeException e) {
            log.error("Ошибка инструмента", e);
            return "Внутренняя ошибка инструмента. Сообщи пользователю, что действие не выполнено.";
        }
    }

    private String describe(MealEntry e) {
        ZoneId zone = user.zone();
        return String.format(Locale.ROOT, "id=%d «%s» %s г в %s — %s%s", e.id(), e.name(), fmtG(e.grams()),
                e.eatenAt().atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                SummaryFormatter.nutrition(e.nutrition()),
                "LOW".equals(e.confidence()) || (e.assumptions() != null && !e.assumptions().isBlank())
                        ? " [уверенность: " + DishFormat.confidenceRu(e.confidence())
                        + (e.assumptions() == null || e.assumptions().isBlank() ? "" : "; " + e.assumptions()) + "]"
                        : "");
    }

    private static String fmtG(double g) {
        return g == Math.rint(g) ? String.valueOf((long) g) : String.valueOf(g);
    }

    private static DishVersion.Kind parseKind(String kind) {
        try {
            return DishVersion.Kind.valueOf(kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("kind должен быть RECIPE или PRODUCT");
        }
    }

    private static String normConfidence(String c) {
        String u = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "LOW", "HIGH" -> u;
            default -> "MEDIUM";
        };
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("дата должна быть в формате YYYY-MM-DD");
        }
    }

    /** Пусто = сейчас; без смещения = локальное время пользователя. */
    OffsetDateTime parseTime(String s) {
        ZoneId zone = user.zone();
        if (s == null || s.isBlank()) {
            return OffsetDateTime.now(zone);
        }
        String t = s.trim();
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException ignored) {
            // пробуем дальше
        }
        try {
            return ZonedDateTime.parse(t).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // пробуем дальше
        }
        try {
            return LocalDateTime.parse(t).atZone(zone).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // пробуем дальше
        }
        try {
            LocalDate d = LocalDate.parse(t);
            return d.atTime(12, 0).atZone(zone).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("время должно быть в формате ISO-8601, например 2025-03-01T13:30");
        }
    }
}
