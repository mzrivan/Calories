package kz.calories.stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kz.calories.energy.ActivityRepository;
import kz.calories.energy.ActivityService;
import kz.calories.energy.DayBalance;
import kz.calories.meal.DayTotals;
import kz.calories.meal.MealService;
import kz.calories.user.AppUser;
import org.springframework.stereotype.Service;

/** Статистика за неделю / месяц / всё время. Сегодняшний день не входит — он ещё не закончен. */
@Service
public class StatsService {

    public enum Period {
        WEEK("Неделя", 7), MONTH("Месяц", 30), ALL("Всё время", null);

        final String title;
        final Integer days;

        Period(String title, Integer days) {
            this.title = title;
            this.days = days;
        }
    }

    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private final MealService meals;
    private final ActivityService activity;

    public StatsService(MealService meals, ActivityService activity) {
        this.meals = meals;
        this.activity = activity;
    }

    public PeriodStats stats(AppUser user, Period period) {
        LocalDate today = meals.today(user);
        LocalDate to = today.minusDays(1);
        LocalDate from = period.days == null ? EPOCH : to.minusDays(period.days - 1L);
        Map<LocalDate, DayTotals> totals = meals.dailyTotals(user, from, to); // отсортировано по дням
        if (period.days == null) {
            from = totals.keySet().stream().findFirst().orElse(to);
        }
        List<PeriodStats.Day> days = new ArrayList<>();
        for (Map.Entry<LocalDate, DayTotals> e : totals.entrySet()) {
            DayBalance b = activity.balance(user, e.getKey());
            days.add(new PeriodStats.Day(e.getKey(), e.getValue().nutrition(), b.spent(),
                    b.known() && b.stepsKcal() == null));
        }
        // Вес: на начало периода (или первое взвешивание после него) и самый свежий на сегодня
        Optional<ActivityRepository.Row> start = activity.latestWeightRow(user, from);
        if (start.isEmpty()) {
            start = activity.firstWeightRow(user, from);
        }
        Optional<ActivityRepository.Row> end = activity.latestWeightRow(user, today);
        return PeriodStats.of(period.title, from, to, days, start.map(StatsService::weight).orElse(null),
                end.map(StatsService::weight).orElse(null));
    }

    private static PeriodStats.Weight weight(ActivityRepository.Row r) {
        return new PeriodStats.Weight(r.day(), r.weightKg());
    }
}
