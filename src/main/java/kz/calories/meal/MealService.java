package kz.calories.meal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.calories.dish.DishVersion;
import kz.calories.user.AppUser;
import kz.calories.validation.Nutrition;
import org.springframework.stereotype.Service;

@Service
public class MealService {

    private final MealRepository repo;

    public MealService(MealRepository repo) {
        this.repo = repo;
    }

    /** Масштабирование без LLM: значения на 100 г × граммы. */
    public MealEntry logMeal(AppUser user, DishVersion version, double grams, OffsetDateTime eatenAt) {
        Nutrition n = version.per100().forGrams(grams).rounded();
        long id = repo.insert(user.id(), eatenAt, version.name(), version.id(), grams, n, version.confidence(),
                version.assumptions());
        return repo.find(user.id(), id).orElseThrow();
    }

    public MealEntry logEstimate(AppUser user, String name, double grams, Nutrition per100, OffsetDateTime eatenAt,
                                 String confidence, String assumptions) {
        Nutrition n = per100.forGrams(grams).rounded();
        String note = "приблизительная оценка" + (assumptions == null || assumptions.isBlank() ? "" : "; " + assumptions);
        long id = repo.insert(user.id(), eatenAt, name, null, grams, n, confidence, note);
        return repo.find(user.id(), id).orElseThrow();
    }

    /** Запись одной позиции пачки: значения на 100 г масштабируются кодом. */
    public MealEntry logLine(AppUser user, String name, Long dishVersionId, double grams, Nutrition per100,
                             OffsetDateTime eatenAt, String confidence, String assumptions, UUID batchId) {
        Nutrition n = per100.forGrams(grams).rounded();
        long id = repo.insert(user.id(), eatenAt, name, dishVersionId, grams, n, confidence, assumptions, batchId);
        return repo.find(user.id(), id).orElseThrow();
    }

    public int deactivateBatch(AppUser user, UUID batchId) {
        return repo.deactivateBatch(user.id(), batchId);
    }

    public Optional<MealEntry> find(AppUser user, long id) {
        return repo.find(user.id(), id);
    }

    public Optional<MealEntry> last(AppUser user) {
        return repo.lastActive(user.id());
    }

    public boolean update(AppUser user, long id, OffsetDateTime eatenAt, double grams, Nutrition n) {
        return repo.update(user.id(), id, eatenAt, grams, n.rounded());
    }

    public Optional<MealEntry> deactivate(AppUser user, long id) {
        Optional<MealEntry> e = repo.find(user.id(), id);
        if (e.isEmpty() || !repo.deactivate(user.id(), id)) {
            return Optional.empty();
        }
        return e;
    }

    public List<MealEntry> entriesOfDay(AppUser user, LocalDate day) {
        return repo.between(user.id(), start(day, user.zone()), start(day.plusDays(1), user.zone()));
    }

    public DayTotals totalsOfDay(AppUser user, LocalDate day) {
        return repo.totals(user.id(), start(day, user.zone()), start(day.plusDays(1), user.zone()));
    }

    public LocalDate today(AppUser user) {
        return LocalDate.now(user.zone());
    }

    /** Блок «Итого за сегодня» (+ остаток при заданных целях) для данного пользователя в его часовом поясе. */
    public String todayBlock(AppUser user) {
        return SummaryFormatter.totalsBlock(totalsOfDay(user, today(user)), user.goals());
    }

    private static OffsetDateTime start(LocalDate day, ZoneId zone) {
        ZonedDateTime z = day.atStartOfDay(zone);
        return z.toOffsetDateTime();
    }
}
