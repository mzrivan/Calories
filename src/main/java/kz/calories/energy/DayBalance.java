package kz.calories.energy;

import java.time.LocalDate;
import java.util.List;

/**
 * Расход и баланс за день. spent == null — посчитать нельзя (см. missing).
 * stepsKcal == null при заданном spent — шагов нет, расход неполный (только обмен).
 * tefKcal — термический эффект съеденной за день пищи (по белкам/углеводам/жирам).
 */
public record DayBalance(LocalDate day, double eatenKcal, Double weightKg, Integer steps, Double bmrKcal,
                         Double tefKcal, Double stepsKcal, double activityKcal, List<ActivityRepository.Entry> activities,
                         Double spent, List<String> missing) {

    public boolean known() {
        return spent != null;
    }

    /** Съедено − потрачено: минус = дефицит. */
    public Double diff() {
        return spent == null ? null : eatenKcal - spent;
    }
}
