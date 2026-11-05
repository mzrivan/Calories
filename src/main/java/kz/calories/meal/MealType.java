package kz.calories.meal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Приём пищи. Определяется по ключевым словам в сообщении (завтрак/обед/ужин); всё остальное — OTHER. */
public enum MealType {
    BREAKFAST("Завтрак", "🍳"),
    LUNCH("Обед", "🍲"),
    DINNER("Ужин", "🍽"),
    OTHER("Остальное", "🍎");

    private final String label;
    private final String emoji;

    MealType(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    /** Результат поиска ключевого слова: тип, текст без ключевых слов, найдено ли несколько разных типов. */
    public record Extracted(MealType type, String text, boolean ambiguous) {}

    /** «на обед», «обед:», «позавтракал», «за ужином» и т.п.; предлог и знак после слова съедаются вместе с ним. */
    private static final Pattern KEYWORD = Pattern.compile(
            "(?:(?<![\\p{L}])(?:на|в|за|после|перед)\\s+)?(?<![\\p{L}])(?:по)?(завтрак|обед|ужин)\\p{L}*\\s*[:,\\-–—]?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Находит ключевые слова приёма пищи и вырезает их из текста. Нет слов — OTHER и исходный текст. */
    public static Extracted extract(String text) {
        if (text == null) {
            return new Extracted(OTHER, "", false);
        }
        Matcher m = KEYWORD.matcher(text);
        MealType found = null;
        boolean ambiguous = false;
        while (m.find()) {
            MealType t = fromStem(m.group(1));
            if (found != null && found != t) {
                ambiguous = true;
            }
            found = t;
        }
        if (found == null) {
            return new Extracted(OTHER, text, false);
        }
        return new Extracted(found, KEYWORD.matcher(text).replaceAll(" ").trim(), ambiguous);
    }

    /** Значение параметра инструмента: BREAKFAST/… или русское слово; пусто — null (не задано). */
    public static MealType parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String u = s.trim().toUpperCase(Locale.ROOT);
        for (MealType t : values()) {
            if (t.name().equals(u)) {
                return t;
            }
        }
        MealType t = extract(s.trim()).type();
        if (t != OTHER) {
            return t;
        }
        if ("ОСТАЛЬНОЕ".equals(u)) {
            return OTHER;
        }
        throw new IllegalArgumentException("mealType должен быть BREAKFAST, LUNCH, DINNER или OTHER");
    }

    private static MealType fromStem(String stem) {
        return switch (stem.toLowerCase(Locale.ROOT)) {
            case "завтрак" -> BREAKFAST;
            case "обед" -> LUNCH;
            default -> DINNER;
        };
    }
}
