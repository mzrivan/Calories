package kz.calories.dish;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import kz.calories.meal.SummaryFormatter;

/** Текстовые представления блюд (формирует код, не LLM). */
public final class DishFormat {
    private DishFormat() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static String confidenceRu(String c) {
        return switch (c == null ? "" : c) {
            case "HIGH" -> "высокая";
            case "LOW" -> "низкая";
            default -> "средняя";
        };
    }

    public static String card(DraftPayload p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.kind() == DishVersion.Kind.RECIPE ? "🍲 Новая версия рецепта: " : "🛒 Новый продукт: ")
                .append(p.name()).append('\n');
        sb.append("На 100 г: ").append(SummaryFormatter.nutrition(p.per100())).append('\n');
        if (p.kind() == DishVersion.Kind.RECIPE && p.cookedWeightG() != null) {
            sb.append("Вес готового: ").append(SummaryFormatter.num(p.cookedWeightG())).append(" г");
            if (p.totalKcal() != null) {
                sb.append(", всего ").append(SummaryFormatter.num(p.totalKcal())).append(" ккал");
            }
            sb.append('\n');
        }
        if (p.method() != null && !p.method().isBlank()) {
            sb.append("Метод: ").append(p.method()).append('\n');
        }
        sb.append("Уверенность: ").append(confidenceRu(p.confidence())).append('\n');
        if (p.assumptions() != null && !p.assumptions().isBlank()) {
            sb.append("Допущения: ").append(p.assumptions()).append('\n');
        }
        sb.append("\nСохранить? Поправить можно текстом, например «убери масло» или «готового вышло 4 кг».");
        return sb.toString();
    }

    public static String line(DishVersion v) {
        return String.format("#%d %s (%s, версия от %s): на 100 г — %s", v.id(), v.name(),
                v.kind() == DishVersion.Kind.RECIPE ? "рецепт" : "продукт", v.createdAt().format(DATE),
                SummaryFormatter.nutrition(v.per100()));
    }
}
