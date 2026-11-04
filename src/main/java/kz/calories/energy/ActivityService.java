package kz.calories.energy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kz.calories.meal.MealService;
import kz.calories.user.AppUser;
import org.springframework.stereotype.Service;

/** Вес, шаги и расход калорий по дням. Данные за день можно обновлять сколько угодно раз. */
@Service
public class ActivityService {

    private final ActivityRepository repo;
    private final EnergyCalculator calc;
    private final MealService meals;

    public ActivityService(ActivityRepository repo, EnergyCalculator calc, MealService meals) {
        this.repo = repo;
        this.calc = calc;
        this.meals = meals;
    }

    /** Сохраняет вес и/или шаги за день, пересчитывает и сохраняет расход; возвращает баланс дня. */
    public DayBalance record(AppUser user, LocalDate day, Double weightKg, Integer steps) {
        if (weightKg != null && (weightKg < 20 || weightKg > 400)) {
            throw new IllegalArgumentException("вес должен быть в диапазоне 20–400 кг");
        }
        if (steps != null && (steps < 0 || steps > 200_000)) {
            throw new IllegalArgumentException("шаги должны быть в диапазоне 0–200000");
        }
        repo.upsert(user.id(), day, weightKg, steps, null, null, null); // сначала данные, затем расчёт
        DayBalance b = balance(user, day);
        repo.upsert(user.id(), day, null, null, b.bmrKcal(), b.stepsKcal(), b.spent());
        return b;
    }

    /** Пересчитывает сохранённый расход дня (например, после смены профиля). */
    public void recompute(AppUser user, LocalDate day) {
        if (repo.find(user.id(), day).isPresent()) {
            DayBalance b = balance(user, day);
            repo.upsert(user.id(), day, null, null, b.bmrKcal(), b.stepsKcal(), b.spent());
        }
    }

    /** Живой расчёт по текущим данным (без записи). */
    public DayBalance balance(AppUser user, LocalDate day) {
        double eaten = meals.totalsOfDay(user, day).nutrition().kcal();
        Optional<ActivityRepository.Row> row = repo.find(user.id(), day);
        Integer steps = row.map(ActivityRepository.Row::steps).orElse(null);
        Double weight = row.map(ActivityRepository.Row::weightKg).orElse(null);
        if (weight == null) {
            weight = repo.latestWeight(user.id(), day).orElse(null);
        }
        AppUser.Profile p = user.profile();
        List<String> missing = new ArrayList<>();
        if (!p.complete()) {
            missing.add("профиль: «рост 178, возраст 35, пол м»");
        }
        if (weight == null) {
            missing.add("вес: «вес 82,5»");
        }
        if (!missing.isEmpty()) {
            return new DayBalance(day, eaten, weight, steps, null, null, null, null, missing);
        }
        int age = day.getYear() - p.birthYear();
        double bmr = calc.bmr(weight, p.heightCm(), age, p.sex());
        double base = calc.baseExpenditure(bmr);
        Double stepsKcal = steps == null ? null : calc.stepsKcal(steps, weight, p.heightCm());
        double spent = base + (stepsKcal == null ? 0 : stepsKcal);
        return new DayBalance(day, eaten, weight, steps, round(bmr), round(base), round(stepsKcal), round(spent), List.of());
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
