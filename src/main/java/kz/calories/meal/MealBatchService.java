package kz.calories.meal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import kz.calories.agent.AgentContext;
import kz.calories.agent.AgentReply;
import kz.calories.config.AppProperties;
import kz.calories.dish.DishFormat;
import kz.calories.dish.DishMatcher;
import kz.calories.dish.DishService;
import kz.calories.dish.DishVersion;
import kz.calories.dish.DraftPayload;
import kz.calories.dish.Names;
import kz.calories.llm.AgentLlm;
import kz.calories.parse.ParsedItem;
import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;
import kz.calories.validation.NutritionValidator;
import kz.calories.validation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Запись пачки блюд из разобранного сообщения. Без LLM, пока всё находится в справочнике:
 * точные совпадения пишутся сразу, примерные и оценки ИИ — после подтверждения кнопкой.
 * Незнакомые блюда уходят в LLM ОДНИМ запросом на всю пачку.
 */
@Service
public class MealBatchService {

    private static final Logger log = LoggerFactory.getLogger(MealBatchService.class);

    public enum Source { EXACT, APPROX, LLM }

    /** Одна позиция пачки; per100 — значения на 100 г, на порцию их масштабирует код. */
    public record Line(String name, String requested, double grams, Nutrition per100, Long dishVersionId,
                       Source source, String confidence, String assumptions) {
        Nutrition portion() {
            return per100.forGrams(grams).rounded();
        }
    }

    /** mealType == null у черновиков, созданных до появления признака, — читается как OTHER. */
    public record Payload(List<Line> lines, OffsetDateTime eatenAt, String originalText, MealType mealType) {
        MealType type() {
            return mealType == null ? MealType.OTHER : mealType;
        }
    }

    public record Confirmed(String text) {}

    private final DishMatcher matcher;
    private final DishService dishes;
    private final MealService meals;
    private final MealDraftRepository drafts;
    private final AgentLlm llm;
    private final NutritionValidator validator;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public MealBatchService(DishMatcher matcher, DishService dishes, MealService meals, MealDraftRepository drafts,
                            AgentLlm llm, NutritionValidator validator, ObjectMapper mapper, AppProperties props,
                            TransactionTemplate tx) {
        this.matcher = matcher;
        this.dishes = dishes;
        this.meals = meals;
        this.drafts = drafts;
        this.llm = llm;
        this.validator = validator;
        this.mapper = mapper;
        this.props = props;
        this.tx = tx;
    }

    public AgentReply process(AppUser user, List<ParsedItem> items, String originalText, MealType mealType) {
        for (ParsedItem it : items) {
            if (it.grams() != null) {
                ValidationResult g = validator.validateGrams(it.grams());
                if (!g.ok()) {
                    return AgentReply.text("«" + it.name() + "»: " + g.message() + ". Ничего не записано, поправьте вес.");
                }
            }
        }
        List<DishMatcher.Match> matches = new ArrayList<>();
        List<Integer> unknown = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DishMatcher.Match m = matcher.match(items.get(i).name());
            matches.add(m);
            if (m.type() == DishMatcher.Type.UNKNOWN) {
                unknown.add(i);
            }
        }

        Map<Integer, AgentLlm.DishEstimate> estimates = new HashMap<>();
        if (!unknown.isEmpty()) {
            List<AgentLlm.EstimateRequest> reqs = new ArrayList<>();
            for (int i : unknown) {
                ParsedItem it = items.get(i);
                reqs.add(new AgentLlm.EstimateRequest(it.name(), it.grams(), it.kcal()));
            }
            List<AgentLlm.DishEstimate> got;
            try {
                got = estimateWithRetry(reqs, originalText);
            } catch (RuntimeException e) {
                log.warn("Оценка незнакомых блюд не удалась: {}", e.toString());
                List<String> names = unknown.stream().map(i -> "«" + items.get(i).name() + "»").toList();
                return AgentReply.text("Не нашёл в справочнике " + String.join(", ", names)
                        + " и не смог оценить (сервис ИИ недоступен или ответил некорректно). Ничего не записано — повторите чуть позже.");
            }
            for (int k = 0; k < unknown.size(); k++) {
                estimates.put(unknown.get(k), got.get(k));
            }
        }

        List<Line> lines = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ParsedItem it = items.get(i);
            DishMatcher.Match m = matches.get(i);
            Line line = m.type() == DishMatcher.Type.UNKNOWN
                    ? llmLine(it, estimates.get(i))
                    : knownLine(it, m);
            if (line == null) {
                failed.add(it.name());
            } else {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return AgentReply.text("Не удалось определить значения для: " + String.join(", ", failed)
                    + ". Напишите КБЖУ на 100 г или рецепт, и я сохраню блюдо.");
        }

        OffsetDateTime at = OffsetDateTime.now(user.zone());
        Payload payload = new Payload(lines, at, originalText, mealType == null ? MealType.OTHER : mealType);
        boolean needsConfirm = lines.stream().anyMatch(l -> l.source() != Source.EXACT);
        if (!needsConfirm) {
            UUID batch = UUID.randomUUID();
            List<MealEntry> entries = tx.execute(s -> commit(user, payload, batch));
            return AgentReply.text(recorded(user, entries, failed, payload.type())).withUndo(batch.toString());
        }
        long draftId = drafts.create(user.id(), toJson(payload), props.drafts().ttlHours());
        return new AgentReply("Проверьте список — после подтверждения запишу в дневник.", List.of(
                AgentContext.DraftCard.meals(draftId, card(lines, failed, payload.type()))));
    }

    /** Подтверждение карточки кнопкой. */
    @Transactional
    public Confirmed confirm(AppUser user, long draftId) {
        MealDraftRepository.Row row = drafts.find(draftId).orElseThrow(() -> new IllegalArgumentException("Черновик не найден"));
        if (row.userId() != user.id()) {
            throw new IllegalArgumentException("Это чужой черновик");
        }
        if (!"PENDING".equals(row.status())) {
            throw new IllegalStateException("Черновик уже обработан или заменён новым");
        }
        if (row.expiresAt().isBefore(OffsetDateTime.now())) {
            drafts.transition(draftId, "EXPIRED");
            throw new IllegalStateException("Черновик устарел, отправьте сообщение заново");
        }
        if (!drafts.transition(draftId, "CONFIRMED")) {
            throw new IllegalStateException("Черновик уже обработан");
        }
        Payload payload = fromJson(row.payloadJson());
        List<MealEntry> entries = commit(user, payload, UUID.randomUUID());
        return new Confirmed(recorded(user, entries, List.of(), payload.type()));
    }

    public boolean cancel(AppUser user, long draftId) {
        return drafts.find(draftId).filter(r -> r.userId() == user.id()).isPresent() && drafts.transition(draftId, "CANCELLED");
    }

    /** Отмена записанной пачки («↩ Отменить»): мягкая деактивация записей. */
    public int undo(AppUser user, UUID batchId) {
        return meals.deactivateBatch(user, batchId);
    }

    // ------------------------------------------------------------------ внутреннее

    List<MealEntry> commit(AppUser user, Payload p, UUID batch) {
        Map<String, Long> created = new HashMap<>();
        List<MealEntry> out = new ArrayList<>();
        for (Line l : p.lines()) {
            Long dvId = l.dishVersionId();
            String assumptions = l.assumptions();
            if (l.source() == Source.LLM) {
                String key = Names.normalize(l.name());
                dvId = created.get(key);
                if (dvId == null) {
                    DishVersion v = dishes.saveVersion(new DraftPayload(l.name(), DishVersion.Kind.PRODUCT, l.per100(), null, null,
                            p.originalText(), "LLM", l.confidence(), l.assumptions()), user.id());
                    dvId = v.id();
                    created.put(key, dvId);
                }
            } else if (l.source() == Source.APPROX && l.requested() != null) {
                dishes.saveAlias(l.requested(), Names.normalize(l.name()));
            }
            out.add(meals.logLine(user, l.name(), dvId, l.grams(), l.per100(), p.eatenAt(), l.confidence(), assumptions, batch, p.type()));
        }
        return out;
    }

    private Line knownLine(ParsedItem it, DishMatcher.Match m) {
        DishVersion v = m.version();
        Double grams = gramsOf(it, v.per100());
        if (grams == null) {
            return null;
        }
        Source src = m.type() == DishMatcher.Type.EXACT ? Source.EXACT : Source.APPROX;
        return new Line(v.name(), it.name(), grams, v.per100(), v.id(), src, v.confidence(), v.assumptions());
    }

    private Line llmLine(ParsedItem it, AgentLlm.DishEstimate e) {
        if (e == null) {
            return null;
        }
        Nutrition per100 = new Nutrition(e.kcalPer100g(), e.proteinPer100g(), e.fatPer100g(), e.carbsPer100g(), e.fiberPer100g()).rounded();
        ValidationResult vr = validator.validatePer100(per100);
        if (!vr.hard().isEmpty()) {
            return null;
        }
        Double grams = gramsOf(it, per100);
        if (grams == null || !validator.validateGrams(grams).ok()) {
            return null;
        }
        String conf = "HIGH".equals(e.confidence()) ? "MEDIUM" : "MEDIUM".equals(e.confidence()) ? "MEDIUM" : "LOW";
        String assumptions = e.assumptions() == null ? "" : e.assumptions().trim();
        if (!vr.soft().isEmpty()) {
            conf = "LOW";
            assumptions = (assumptions.isEmpty() ? "" : assumptions + "; ") + "не прошло проверку: " + vr.message();
        }
        return new Line(capitalize(it.name()), null, grams, per100, null, Source.LLM, conf,
                "оценка ИИ" + (assumptions.isEmpty() ? "" : "; " + assumptions));
    }

    /** Граммы из сообщения либо выведенные из съеденных ккал; null — вывести нельзя. */
    private static Double gramsOf(ParsedItem it, Nutrition per100) {
        if (it.grams() != null) {
            return it.grams();
        }
        if (it.kcal() != null && per100.kcal() >= 1) {
            return Math.round(it.kcal() / per100.kcal() * 1000.0) / 10.0;
        }
        return null;
    }

    private List<AgentLlm.DishEstimate> estimateWithRetry(List<AgentLlm.EstimateRequest> reqs, String text) {
        int attempts = Math.max(1, props.llm().retries());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                List<AgentLlm.DishEstimate> got = llm.estimate(reqs, text);
                if (got == null || got.size() != reqs.size()) {
                    throw new IllegalStateException("LLM вернула " + (got == null ? 0 : got.size()) + " позиций вместо " + reqs.size());
                }
                return got;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Ошибка оценки LLM (попытка {}/{}): {}", attempt, attempts, e.toString());
                if (attempt < attempts) {
                    long base = props.llm().retryInitialDelayMs() * (1L << (attempt - 1));
                    try {
                        Thread.sleep(base + ThreadLocalRandom.current().nextLong(base / 2 + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("нет ответа") : last;
    }

    private String recorded(AppUser user, List<MealEntry> entries, List<String> failed, MealType type) {
        StringBuilder sb = new StringBuilder("✅ Записано");
        if (type != MealType.OTHER) {
            sb.append(" (").append(type.label().toLowerCase(Locale.ROOT)).append(')');
        }
        sb.append(":\n");
        for (MealEntry e : entries) {
            sb.append(String.format(Locale.ROOT, "• %s, %s г — %s%n", e.name(), g(e.grams()), SummaryFormatter.nutrition(e.nutrition())));
        }
        if (entries.size() > 1) {
            Nutrition sum = Nutrition.ZERO;
            for (MealEntry e : entries) {
                sum = sum.plus(e.nutrition());
            }
            sb.append("Всего добавлено: ").append(SummaryFormatter.nutrition(sum)).append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("Не записано (нет данных): ").append(String.join(", ", failed)).append('\n');
        }
        return sb.append('\n').append(meals.todayBlock(user)).toString();
    }

    private static String card(List<Line> lines, List<String> failed, MealType type) {
        StringBuilder sb = new StringBuilder("Проверьте список");
        if (type != MealType.OTHER) {
            sb.append(" (").append(type.label().toLowerCase(Locale.ROOT)).append(')');
        }
        sb.append(":\n");
        for (Line l : lines) {
            sb.append(String.format(Locale.ROOT, "• %s, %s г — %s", l.name(), g(l.grams()), SummaryFormatter.nutrition(l.portion())));
            switch (l.source()) {
                case APPROX -> sb.append("\n   ≈ «").append(l.requested()).append("» принято за «").append(l.name()).append('»');
                case LLM -> sb.append("\n   🤖 оценка ИИ (уверенность: ").append(DishFormat.confidenceRu(l.confidence()))
                        .append("), на 100 г: ").append(SummaryFormatter.nutrition(l.per100()));
                default -> { }
            }
            sb.append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("Без данных, не будет записано: ").append(String.join(", ", failed)).append('\n');
        }
        if (lines.stream().anyMatch(l -> l.source() == Source.LLM)) {
            sb.append("\nОценки ИИ сохранятся в справочник — в следующий раз запишутся сразу.");
        }
        return sb.toString();
    }

    private static String g(double grams) {
        return grams == Math.rint(grams) ? String.valueOf((long) grams) : String.valueOf(grams);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private String toJson(Payload p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Payload fromJson(String json) {
        try {
            return mapper.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
