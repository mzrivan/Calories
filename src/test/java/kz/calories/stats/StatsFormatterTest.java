package kz.calories.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kz.calories.validation.Nutrition;
import org.junit.jupiter.api.Test;

class StatsFormatterTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 4);
    private static final LocalDate TO = LocalDate.of(2026, 11, 10);

    private static PeriodStats.Day day(int d, double kcal, Double spent, boolean noSteps) {
        return new PeriodStats.Day(LocalDate.of(2026, 11, d), new Nutrition(kcal, 100, 60, 200, 20.0), spent, noSteps);
    }

    @Test
    void deficitAndExpectedWeight() {
        PeriodStats s = PeriodStats.of("Неделя", FROM, TO,
                List.of(day(4, 2000, 2500.0, false), day(5, 2200, 2400.0, false)),
                new PeriodStats.Weight(FROM, 82.5), new PeriodStats.Weight(TO, 82.1));
        assertThat(s.avgBalance()).isEqualTo(-350.0);
        assertThat(s.trend()).isEqualTo(PeriodStats.Trend.DEFICIT);
        assertThat(s.expectedChangeKg()).isCloseTo(-700.0 / 7700.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.actualChangeKg()).isCloseTo(-0.4, org.assertj.core.data.Offset.offset(1e-9));

        String text = StatsFormatter.format(s);
        assertThat(text).contains("Неделя: 04.11–10.11.2026").contains("2 из 7")
                .contains("Съедено: 2100 ккал").contains("Расход: 2450 ккал")
                .contains("Баланс: −350 ккал (дефицит)")
                .contains("Б 100 · Ж 60 · У 200 · клетчатка 20 г")
                .contains("Вес: 82.5 → 82.1 кг (−0.4 кг").contains("Ожидалось по балансу: −0.1 кг");
    }

    @Test
    void surplusAndMaintenance() {
        PeriodStats up = PeriodStats.of("Месяц", FROM, TO, List.of(day(4, 3000, 2500.0, false)), null, null);
        assertThat(up.trend()).isEqualTo(PeriodStats.Trend.SURPLUS);
        assertThat(StatsFormatter.format(up)).contains("+500 ккал (профицит)").contains("Вес: нет данных");

        PeriodStats flat = PeriodStats.of("Месяц", FROM, TO, List.of(day(4, 2550, 2500.0, false)), null, null);
        assertThat(flat.trend()).isEqualTo(PeriodStats.Trend.MAINTENANCE);
        assertThat(StatsFormatter.format(flat)).contains("(поддержка)");
    }

    @Test
    void missingProfileGivesNoSpendAndNoStepsNote() {
        PeriodStats s = PeriodStats.of("Неделя", FROM, TO,
                List.of(day(4, 2000, null, false), day(5, 2000, 2400.0, true)), null, null);
        assertThat(s.spentDays()).isEqualTo(1);
        assertThat(s.noStepsDays()).isEqualTo(1);
        assertThat(StatsFormatter.format(s)).contains("расход посчитан по 1 из 2").contains("нет шагов");

        PeriodStats none = PeriodStats.of("Неделя", FROM, TO, List.of(day(4, 2000, null, false)), null, null);
        assertThat(StatsFormatter.format(none)).contains("Расход: не посчитать").doesNotContain("Баланс");
    }

    @Test
    void singleWeighInHasNoDynamicsAndEmptyPeriod() {
        PeriodStats s = PeriodStats.of("Неделя", FROM, TO, List.of(day(4, 2000, 2400.0, false)),
                new PeriodStats.Weight(TO, 82.0), new PeriodStats.Weight(TO, 82.0));
        assertThat(s.actualChangeKg()).isNull();
        assertThat(StatsFormatter.format(s)).contains("нужно ещё одно взвешивание");

        assertThat(StatsFormatter.format(PeriodStats.of("Неделя", FROM, TO, List.of(), null, null)))
                .contains("Нет записей еды");
    }
}
