package kz.calories.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.calories.validation.Nutrition;

/**
 * Строка нового продукта «название ккал/белки/жиры/углеводы[/клетчатка]» (значения на 100 г),
 * например «Творог президент 56/12/0.1/1.8». Чистая функция, без Spring и БД.
 */
public final class ProductLineParser {

    public record Product(String name, Nutrition per100) {}

    private static final String N = "(\\d+(?:[.,]\\d+)?)";
    private static final String SEP = "\\s*/\\s*";
    private static final Pattern LINE = Pattern.compile(
            "^(.+?)\\s*[:\\-–—]?\\s*" + N + SEP + N + SEP + N + SEP + N + "(?:" + SEP + N + ")?\\s*$",
            Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final int MAX_NAME = 120;

    private ProductLineParser() {}

    public static Optional<Product> parse(String text) {
        if (text == null || text.contains("\n")) {
            return Optional.empty();
        }
        Matcher m = LINE.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String name = m.group(1).trim().replaceAll("^[\"'«»]+|[\"'«»:\\-–—\\s]+$", "").trim();
        if (name.length() > MAX_NAME || !name.matches(".*\\p{L}{2}.*")) {
            return Optional.empty();
        }
        Double fiber = m.group(6) == null ? null : num(m.group(6));
        return Optional.of(new Product(name, new Nutrition(num(m.group(2)), num(m.group(3)), num(m.group(4)),
                num(m.group(5)), fiber).rounded()));
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
