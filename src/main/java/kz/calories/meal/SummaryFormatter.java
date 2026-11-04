package kz.calories.meal;

import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;

/** Форматирование чисел и блока «Итого за сегодня» — только кодом, LLM цифры не пишет. */
public final class SummaryFormatter {
    private SummaryFormatter() {}

    public static String nutrition(Nutrition n) {
        String s = macros(n);
        if (n.fiber() != null) {
            s += " · Клетч. " + num(n.fiber());
        }
        return s;
    }

    private static String macros(Nutrition n) {
        return num(n.kcal()) + " ккал · Б " + num(n.protein()) + " · Ж " + num(n.fat()) + " · У " + num(n.carbs());
    }

    public static String totalsBlock(DayTotals totals, AppUser.Goals goals) {
        Nutrition n = totals.nutrition();
        StringBuilder sb = new StringBuilder("Итого за сегодня: ")
                .append(macros(n));
        if (n.fiber() != null) {
            sb.append(" · Клетч. ").append(num(n.fiber()));
        }
        if (goals != null && goals.any()) {
            StringBuilder left = new StringBuilder();
            add(left, "", goals.kcal(), n.kcal(), " ккал");
            add(left, "Б ", goals.protein(), n.protein(), "");
            add(left, "Ж ", goals.fat(), n.fat(), "");
            add(left, "У ", goals.carbs(), n.carbs(), "");
            if (n.fiber() != null) {
                add(left, "Клетч. ", goals.fiber(), n.fiber(), "");
            }
            if (!left.isEmpty()) {
                sb.append("\nОсталось: ").append(left);
            }
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String label, Double goal, double eaten, String suffix) {
        if (goal == null) {
            return;
        }
        double remaining = Math.round((goal - eaten) * 10.0) / 10.0;
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
        sb.append(label).append(remaining < 0 ? "+" + num(-remaining) : num(remaining)).append(suffix);
    }

    /** Число с точностью до 0.1; целые без «.0» (как хранится в БД). */
    public static String num(double v) {
        double rounded = Math.round(v * 10.0) / 10.0;
        if (rounded == 0) {
            return "0";
        }
        return rounded == Math.rint(rounded)
                ? String.valueOf((long) rounded)
                : String.format(java.util.Locale.ROOT, "%.1f", rounded);
    }
}
