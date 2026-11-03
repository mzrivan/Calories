package kz.calories.meal;

import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;

/** Форматирование чисел и блока «Итого за сегодня» — только кодом, LLM цифры не пишет. */
public final class SummaryFormatter {
    private SummaryFormatter() {}

    public static String nutrition(Nutrition n) {
        String s = String.format("%d ккал · Б %d · Ж %d · У %d", r(n.kcal()), r(n.protein()), r(n.fat()), r(n.carbs()));
        if (n.fiber() != null) {
            s += " · Клетч. " + oneDecimal(n.fiber());
        }
        return s;
    }

    public static String totalsBlock(DayTotals totals, AppUser.Goals goals) {
        Nutrition n = totals.nutrition();
        StringBuilder sb = new StringBuilder("Итого за сегодня: ")
                .append(String.format("%d ккал · Б %d · Ж %d · У %d", r(n.kcal()), r(n.protein()), r(n.fat()), r(n.carbs())));
        if (n.fiber() != null) {
            sb.append(" · Клетч. ").append(r(n.fiber()));
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
        long remaining = r(goal - eaten);
        if (!sb.isEmpty()) {
            sb.append(" · ");
        }
        sb.append(label).append(remaining < 0 ? "+" + (-remaining) : String.valueOf(remaining)).append(suffix);
    }

    private static int r(double v) {
        return (int) Math.round(v);
    }

    private static String oneDecimal(double v) {
        double rounded = Math.round(v * 10.0) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }
}
