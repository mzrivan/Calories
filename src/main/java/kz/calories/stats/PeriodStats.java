package kz.calories.stats;

import java.time.LocalDate;
import java.util.List;
import kz.calories.validation.Nutrition;

/**
 * Статистика за период. Чистые вычисления, без БД. Учитываются только дни, где есть записи еды;
 * расход и баланс — только по дням, где расход удалось посчитать (нужны профиль и вес).
 */
public record PeriodStats(String title, LocalDate from, LocalDate to, int periodDays, int loggedDays,
                          Nutrition avgEaten, int spentDays, Double avgSpent, Double avgBalance, int noStepsDays,
                          Weight startWeight, Weight endWeight, Double expectedChangeKg) {

    /** Сколько ккал соответствует 1 кг массы тела (общепринятая оценка). */
    public static final double KCAL_PER_KG = 7700.0;
    /** Баланс в пределах ±порога ккал/день считаем поддержкой. */
    public static final double MAINTENANCE_KCAL = 100.0;

    public record Weight(LocalDate day, double kg) {}

    /** Один день: съедено и (если известно) потрачено. */
    public record Day(LocalDate day, Nutrition eaten, Double spent, boolean noSteps) {}

    public enum Trend { DEFICIT, SURPLUS, MAINTENANCE }

    public static PeriodStats of(String title, LocalDate from, LocalDate to, List<Day> days, Weight start, Weight end) {
        int periodDays = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        int n = days.size();
        Nutrition sum = Nutrition.ZERO;
        double fiberSum = 0;
        boolean anyFiber = false;
        for (Day d : days) {
            sum = sum.plus(d.eaten());
            if (d.eaten().fiber() != null) {
                anyFiber = true;
                fiberSum += d.eaten().fiber();
            }
        }
        Nutrition avg = n == 0 ? Nutrition.ZERO
                : new Nutrition(sum.kcal() / n, sum.protein() / n, sum.fat() / n, sum.carbs() / n,
                        anyFiber ? fiberSum / n : null);
        List<Day> known = days.stream().filter(d -> d.spent() != null).toList();
        int spentDays = known.size();
        Double avgSpent = null;
        Double avgBalance = null;
        Double expected = null;
        if (spentDays > 0) {
            double spentSum = known.stream().mapToDouble(Day::spent).sum();
            double balanceSum = known.stream().mapToDouble(d -> d.eaten().kcal() - d.spent()).sum();
            avgSpent = spentSum / spentDays;
            avgBalance = balanceSum / spentDays;
            expected = balanceSum / KCAL_PER_KG;
        }
        int noSteps = (int) known.stream().filter(Day::noSteps).count();
        return new PeriodStats(title, from, to, periodDays, n, avg, spentDays, avgSpent, avgBalance, noSteps,
                start, end, expected);
    }

    public Trend trend() {
        if (avgBalance == null || Math.abs(avgBalance) < MAINTENANCE_KCAL) {
            return Trend.MAINTENANCE;
        }
        return avgBalance < 0 ? Trend.DEFICIT : Trend.SURPLUS;
    }

    /** Фактическое изменение веса; null — меньше двух разных взвешиваний. */
    public Double actualChangeKg() {
        if (startWeight == null || endWeight == null || !endWeight.day().isAfter(startWeight.day())) {
            return null;
        }
        return endWeight.kg() - startWeight.kg();
    }
}
