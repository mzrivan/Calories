package kz.calories.parse;

/** Позиция из сообщения: название и либо граммы, либо ккал («хлебец на 30ккал»). */
public record ParsedItem(String name, Double grams, Double kcal) {

    public static ParsedItem ofGrams(String name, double grams) {
        return new ParsedItem(name, grams, null);
    }

    public static ParsedItem ofKcal(String name, double kcal) {
        return new ParsedItem(name, null, kcal);
    }
}
