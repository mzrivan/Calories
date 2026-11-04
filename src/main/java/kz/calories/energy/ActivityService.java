package kz.calories.energy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kz.calories.meal.MealService;
import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;
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
        Nutrition eatenNutrition = meals.totalsOfDay(user, day).nutrition();
        double eaten = eatenNutrition.kcal();
        Optional<ActivityRepository.Row> row = repo.find(user.id(), day);
        Integer steps = row.map(ActivityRepository.Row::steps).orElse(null);
        Double weight = row.map(ActivityRepository.Row::weightKg).orElse(null);
        if (weight == null) {
            weight = repo.latestWeight(user.id(), day).orElse(null);
        }
        List<ActivityRepository.Entry> activities = repo.entriesOfDay(user.id(), day);
        double activityKcal = activities.stream().mapToDouble(ActivityRepository.Entry::kcal).sum();
        AppUser.Profile p = user.profile();
        List<String> missing = new ArrayList<>();
        if (!p.complete()) {
            missing.add("профиль: «рост 178, возраст 35, пол м»");
        }
        if (weight == null) {
            missing.add("вес: «вес 82,5»");
        }
        if (!missing.isEmpty()) {
            return new DayBalance(day, eaten, weight, steps, null, null, null, activityKcal, activities, null, missing);
        }
        int age = day.getYear() - p.birthYear();
        double bmr = calc.bmr(weight, p.heightCm(), age, p.sex());
        double tef = calc.tef(eatenNutrition);
        Double stepsKcal = steps == null ? null : calc.stepsKcal(steps, weight, p.heightCm());
        double spent = bmr + tef + (stepsKcal == null ? 0 : stepsKcal) + activityKcal;
        return new DayBalance(day, eaten, weight, steps, round(bmr), round(tef), round(stepsKcal), round(activityKcal),
                activities, round(spent), List.of());
    }

    /** Текущий вес на день (последний известный) — для расчётов и подсказки LLM. */
    public Optional<Double> currentWeight(LocalDate day, long userId) {
        return repo.latestWeight(userId, day);
    }

    /**
     * Записывает произвольную активность. Расход: либо явный kcal (пользователь назвал сам), либо по MET:
     * (MET − 1) × вес × часы — «−1» убирает основной обмен, он уже входит в расход дня. Возвращает запись.
     */
    public ActivityRepository.Entry logActivity(AppUser user, LocalDate day, String name, double minutes, Double met,
                                                Double kcalOverride, String confidence, String assumptions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("нужно название активности");
        }
        if (!(minutes > 0 && minutes <= 1440)) {
            throw new IllegalArgumentException("длительность должна быть от 1 до 1440 минут");
        }
        double kcal;
        if (kcalOverride != null) {
            kcal = kcalOverride;
        } else {
            if (met == null) {
                throw new IllegalArgumentException("нужен met или kcal");
            }
            if (met < 1 || met > 20) {
                throw new IllegalArgumentException("met должен быть в диапазоне 1–20");
            }
            double weight = repo.latestWeight(user.id(), day).orElseThrow(
                    () -> new IllegalArgumentException("не известен вес пользователя — попроси: «вес 82,5»"));
            kcal = (met - 1) * weight * minutes / 60.0;
        }
        kcal = Math.round(kcal * 10.0) / 10.0;
        if (kcal < 0 || kcal > 10_000) {
            throw new IllegalArgumentException("расход активности должен быть в диапазоне 0–10000 ккал");
        }
        ActivityRepository.Entry e = repo.addEntry(user.id(), day, name.trim(), minutes, met, kcal, confidence, assumptions);
        persist(user, day);
        return e;
    }

    /** Мягко убирает свою активность по id и пересчитывает день. */
    public Optional<ActivityRepository.Entry> deactivateActivity(AppUser user, long id) {
        Optional<ActivityRepository.Entry> e = repo.deactivateEntry(user.id(), id);
        e.ifPresent(x -> persist(user, x.day()));
        return e;
    }

    private void persist(AppUser user, LocalDate day) {
        DayBalance b = balance(user, day);
        if (b.known()) {
            repo.upsert(user.id(), day, null, null, b.bmrKcal(), b.stepsKcal(), b.spent());
        }
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
