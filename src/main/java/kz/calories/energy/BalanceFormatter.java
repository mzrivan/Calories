package kz.calories.energy;

import java.util.Locale;

/** Текст блока «Расход / Баланс» — формирует код, не LLM. */
public final class BalanceFormatter {
    private BalanceFormatter() {}

    public static String block(DayBalance b) {
        if (!b.known()) {
            return "Расход не посчитать — не хватает данных. Напишите — " + String.join("; ", b.missing()) + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Расход: ").append(num(b.spent())).append(" ккал (обмен ").append(num(b.bmrKcal()))
                .append(" + переваривание пищи ").append(num(b.tefKcal()));
        if (b.stepsKcal() != null) {
            sb.append(" + шаги ").append(num(b.stepsKcal())).append(", ").append(b.steps()).append(" шагов");
        }
        if (!b.activities().isEmpty()) {
            sb.append(" + активность ").append(num(b.activityKcal()));
        }
        sb.append(")\n");
        for (ActivityRepository.Entry a : b.activities()) {
            sb.append("• ").append(a.name()).append(", ").append(num(a.minutes())).append(" мин — ")
                    .append(num(a.kcal())).append(" ккал (id=").append(a.id()).append(")\n");
        }
        double diff = Math.round(b.diff() * 10.0) / 10.0;
        sb.append("Съедено ").append(num(b.eatenKcal())).append(" ккал · Баланс: ")
                .append(diff > 0 ? "+" : "").append(num(diff)).append(" ккал (")
                .append(diff < 0 ? "дефицит" : diff > 0 ? "профицит" : "ровно").append(")");
        if (b.stepsKcal() == null) {
            sb.append("\nШагов за этот день нет — расход неполный. Напишите, например: «шагов 9500».");
        }
        return sb.toString();
    }

    private static String num(double v) {
        return kz.calories.meal.SummaryFormatter.num(v);
    }
}
