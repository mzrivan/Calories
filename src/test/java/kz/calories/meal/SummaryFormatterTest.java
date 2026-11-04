package kz.calories.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import kz.calories.dish.Names;
import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;
import org.junit.jupiter.api.Test;

class SummaryFormatterTest {

    private static final AppUser.Goals NO_GOALS = new AppUser.Goals(null, null, null, null, null);

    @Test
    void totalsWithFiberNoGoals() {
        DayTotals t = new DayTotals(new Nutrition(1450, 78, 52, 160, 21.0), 3);
        assertThat(SummaryFormatter.totalsBlock(t, NO_GOALS))
                .isEqualTo("Итого за сегодня: 1450 ккал · Б 78 · Ж 52 · У 160 · Клетч. 21");
    }

    @Test
    void fiberOmittedWhenNoData() {
        DayTotals t = new DayTotals(new Nutrition(500, 20, 10, 80, null), 1);
        assertThat(SummaryFormatter.totalsBlock(t, NO_GOALS)).doesNotContain("Клетч");
    }

    @Test
    void remainingAndOvershoot() {
        DayTotals t = new DayTotals(new Nutrition(1450, 78, 52, 160, 21.0), 3);
        AppUser.Goals goals = new AppUser.Goals(2000.0, 90.0, 60.0, 200.0, 30.0);
        assertThat(SummaryFormatter.totalsBlock(t, goals)).endsWith("Осталось: 550 ккал · Б 12 · Ж 8 · У 40 · Клетч. 9");
        DayTotals over = new DayTotals(new Nutrition(2120, 78, 52, 160, 21.0), 3);
        assertThat(SummaryFormatter.totalsBlock(over, goals)).contains("+120 ккал");
    }

    @Test
    void decimalsAreKept() {
        DayTotals t = new DayTotals(new Nutrition(1450.6, 78.3, 52.04, 160.5, 21.2), 3);
        assertThat(SummaryFormatter.totalsBlock(t, NO_GOALS))
                .isEqualTo("Итого за сегодня: 1450.6 ккал · Б 78.3 · Ж 52 · У 160.5 · Клетч. 21.2");
        AppUser.Goals goals = new AppUser.Goals(2000.0, null, null, null, null);
        assertThat(SummaryFormatter.totalsBlock(t, goals)).endsWith("Осталось: 549.4 ккал");
        assertThat(SummaryFormatter.num(-0.04)).isEqualTo("0");
    }

    @Test
    void normalizeNames() {
        assertThat(Names.normalize("  Борщ   Зелёный ")).isEqualTo("борщ зеленый");
    }

    @Test
    void zoneIsUsedForDayBoundaries() {
        // 2025-03-01 23:30 в Алматы (UTC+5) — это ещё 1 марта, хотя в UTC уже 18:30 1 марта; полночь Алматы = 19:00 UTC
        ZoneId almaty = ZoneId.of("Asia/Almaty");
        var start = java.time.LocalDate.of(2025, 3, 2).atStartOfDay(almaty).toOffsetDateTime();
        assertThat(start.toInstant().toString()).isEqualTo("2025-03-01T19:00:00Z");
    }
}
