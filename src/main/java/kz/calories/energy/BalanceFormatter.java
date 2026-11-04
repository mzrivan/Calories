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
        sb.append(String.format(Locale.ROOT, "Расход: %d ккал (обмен %d", r(b.spent()), r(b.baseKcal())));
        if (b.stepsKcal() != null) {
            sb.append(String.format(Locale.ROOT, " + шаги %d, %d шагов", r(b.stepsKcal()), b.steps()));
        }
        sb.append(")\n");
        long diff = r(b.diff());
        sb.append(String.format(Locale.ROOT, "Съедено %d ккал · Баланс: %s%d ккал (%s)", r(b.eatenKcal()),
                diff > 0 ? "+" : "", diff, diff < 0 ? "дефицит" : diff > 0 ? "профицит" : "ровно"));
        if (b.stepsKcal() == null) {
            sb.append("\nШагов за этот день нет — расход неполный. Напишите, например: «шагов 9500».");
        }
        return sb.toString();
    }

    private static long r(double v) {
        return Math.round(v);
    }
}
