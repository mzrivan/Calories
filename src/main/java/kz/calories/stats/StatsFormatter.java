package kz.calories.stats;

import java.time.format.DateTimeFormatter;
import kz.calories.meal.SummaryFormatter;
import kz.calories.validation.Nutrition;

/** Текст статистики — формирует код, не LLM. Коротко: средние за день, баланс, вес. */
public final class StatsFormatter {
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private StatsFormatter() {}

    public static String format(PeriodStats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(s.title()).append(": ").append(s.from().format(DM)).append("–")
                .append(s.to().format(DMY)).append("\n");
        if (s.loggedDays() == 0) {
            return sb.append("Нет записей еды за этот период (сегодняшний день не учитывается).").toString();
        }
        sb.append("Дней с записями: ").append(s.loggedDays()).append(" из ").append(s.periodDays())
                .append(" · цифры — в среднем за день\n\n");

        Nutrition a = s.avgEaten();
        sb.append("Съедено: ").append(whole(a.kcal())).append(" ккал\n");
        if (s.avgSpent() == null) {
            sb.append("Расход: не посчитать — нужны профиль и вес («рост 178, возраст 35, пол м», «вес 82,5»)\n");
        } else {
            sb.append("Расход: ").append(whole(s.avgSpent())).append(" ккал\n");
            sb.append("Баланс: ").append(signed(s.avgBalance())).append(" ккал (").append(trendWord(s.trend())).append(")\n");
            if (s.spentDays() < s.loggedDays()) {
                sb.append("  расход посчитан по ").append(s.spentDays()).append(" из ").append(s.loggedDays())
                        .append(" дн. (где известны профиль и вес)\n");
            }
            if (s.noStepsDays() > 0) {
                sb.append("  на ").append(s.noStepsDays()).append(" дн. нет шагов — расход там занижен\n");
            }
        }
        sb.append("Б ").append(whole(a.protein())).append(" · Ж ").append(whole(a.fat())).append(" · У ")
                .append(whole(a.carbs())).append(" · клетчатка ")
                .append(a.fiber() == null ? "нет данных" : whole(a.fiber()) + " г").append("\n\n");

        Double actual = s.actualChangeKg();
        if (s.endWeight() == null) {
            sb.append("Вес: нет данных — «вес 82,5»");
        } else if (actual == null) {
            sb.append("Вес: ").append(kg(s.endWeight().kg())).append(" кг — для динамики нужно ещё одно взвешивание");
        } else {
            sb.append("Вес: ").append(kg(s.startWeight().kg())).append(" → ").append(kg(s.endWeight().kg()))
                    .append(" кг (").append(signedKg(actual)).append(" кг; ")
                    .append(s.startWeight().day().format(DM)).append(" → ").append(s.endWeight().day().format(DM)).append(")");
        }
        if (s.expectedChangeKg() != null) {
            sb.append("\nОжидалось по балансу: ").append(signedKg(s.expectedChangeKg())).append(" кг (")
                    .append(expectedWord(s.trend())).append(")");
        }
        return sb.toString();
    }

    private static String trendWord(PeriodStats.Trend t) {
        return switch (t) {
            case DEFICIT -> "дефицит";
            case SURPLUS -> "профицит";
            case MAINTENANCE -> "поддержка";
        };
    }

    private static String expectedWord(PeriodStats.Trend t) {
        return switch (t) {
            case DEFICIT -> "дефицит → вес снижается";
            case SURPLUS -> "профицит → вес растёт";
            case MAINTENANCE -> "поддержка → вес почти не меняется";
        };
    }

    private static String whole(double v) {
        return String.valueOf(Math.round(v));
    }

    private static String signed(double v) {
        long r = Math.round(v);
        return r > 0 ? "+" + r : r < 0 ? "−" + (-r) : "0";
    }

    private static String kg(double v) {
        return SummaryFormatter.num(v);
    }

    private static String signedKg(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        if (r == 0) {
            return "0";
        }
        return (r > 0 ? "+" : "−") + SummaryFormatter.num(Math.abs(r));
    }
}
