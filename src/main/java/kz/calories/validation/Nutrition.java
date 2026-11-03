package kz.calories.validation;

/** КБЖУ + клетчатка. fiber == null означает «данных нет». carbs включает fiber. */
public record Nutrition(double kcal, double protein, double fat, double carbs, Double fiber) {

    public static final Nutrition ZERO = new Nutrition(0, 0, 0, 0, null);

    public Nutrition scale(double factor) {
        return new Nutrition(kcal * factor, protein * factor, fat * factor, carbs * factor,
                fiber == null ? null : fiber * factor);
    }

    /** Значения на 100 г: всё блюдо / вес готового × 100. */
    public Nutrition per100(double weightG) {
        return scale(100.0 / weightG);
    }

    /** Масштабирует значения «на 100 г» на граммы порции. */
    public Nutrition forGrams(double grams) {
        return scale(grams / 100.0);
    }

    public Nutrition plus(Nutrition o) {
        Double f;
        if (fiber == null) {
            f = o.fiber;
        } else if (o.fiber == null) {
            f = fiber;
        } else {
            f = fiber + o.fiber;
        }
        return new Nutrition(kcal + o.kcal, protein + o.protein, fat + o.fat, carbs + o.carbs, f);
    }

    /** Округление до 0.1 — так значения хранятся в БД. */
    public Nutrition rounded() {
        return new Nutrition(r(kcal), r(protein), r(fat), r(carbs), fiber == null ? null : r(fiber));
    }

    private static double r(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
