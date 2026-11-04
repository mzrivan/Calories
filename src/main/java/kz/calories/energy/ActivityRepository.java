package kz.calories.energy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityRepository {

    public record Row(LocalDate day, Double weightKg, Integer steps) {}

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(long userId, LocalDate day) {
        return jdbc.sql("select day, weight_kg, steps from daily_activity where user_id = :u and day = :d")
                .param("u", userId).param("d", day).query(this::map).optional();
    }

    /** Последний известный вес на день или раньше. */
    public Optional<Double> latestWeight(long userId, LocalDate day) {
        return jdbc.sql("select weight_kg from daily_activity where user_id = :u and day <= :d and weight_kg is not null "
                        + "order by day desc limit 1")
                .param("u", userId).param("d", day).query(Double.class).optional();
    }

    /** Частичный upsert: переданные вес/шаги заменяют прежние, остальные остаются; расчётные значения перезаписываются. */
    public void upsert(long userId, LocalDate day, Double weightKg, Integer steps, Double bmr, Double stepsKcal, Double total) {
        jdbc.sql("""
                insert into daily_activity (user_id, day, weight_kg, steps, bmr_kcal, steps_kcal, total_kcal)
                values (:u, :d, :w, :s, :b, :sk, :t)
                on conflict (user_id, day) do update set
                    weight_kg = coalesce(excluded.weight_kg, daily_activity.weight_kg),
                    steps = coalesce(excluded.steps, daily_activity.steps),
                    bmr_kcal = excluded.bmr_kcal, steps_kcal = excluded.steps_kcal, total_kcal = excluded.total_kcal,
                    updated_at = now()""")
                .param("u", userId).param("d", day).param("w", weightKg).param("s", steps)
                .param("b", bmr).param("sk", stepsKcal).param("t", total).update();
    }

    /** Произвольная активность; kcal — чистый расход сверх основного обмена. */
    public record Entry(long id, LocalDate day, String name, double minutes, Double met, double kcal, String confidence,
                        String assumptions) {}

    public Entry addEntry(long userId, LocalDate day, String name, double minutes, Double met, double kcal,
                          String confidence, String assumptions) {
        return jdbc.sql("""
                insert into activity_entry (user_id, day, name, minutes, met, kcal, confidence, assumptions)
                values (:u, :d, :n, :m, :met, :k, :c, :a)
                returning id, day, name, minutes, met, kcal, confidence, assumptions""")
                .param("u", userId).param("d", day).param("n", name).param("m", minutes).param("met", met)
                .param("k", kcal).param("c", confidence).param("a", assumptions)
                .query(this::mapEntry).single();
    }

    public List<Entry> entriesOfDay(long userId, LocalDate day) {
        return jdbc.sql("""
                select id, day, name, minutes, met, kcal, confidence, assumptions from activity_entry
                where user_id = :u and day = :d and active order by id""")
                .param("u", userId).param("d", day).query(this::mapEntry).list();
    }

    /** Мягкое удаление своей записи; false — записи нет или она чужая/уже удалена. */
    public Optional<Entry> deactivateEntry(long userId, long id) {
        return jdbc.sql("""
                update activity_entry set active = false, deactivated_at = now()
                where id = :id and user_id = :u and active
                returning id, day, name, minutes, met, kcal, confidence, assumptions""")
                .param("id", id).param("u", userId).query(this::mapEntry).optional();
    }

    private Entry mapEntry(ResultSet rs, int i) throws SQLException {
        double met = rs.getDouble("met");
        Double metBox = rs.wasNull() ? null : met;
        return new Entry(rs.getLong("id"), rs.getObject("day", LocalDate.class), rs.getString("name"),
                rs.getDouble("minutes"), metBox, rs.getDouble("kcal"), rs.getString("confidence"),
                rs.getString("assumptions"));
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        double w = rs.getDouble("weight_kg");
        Double weight = rs.wasNull() ? null : w;
        int s = rs.getInt("steps");
        Integer steps = rs.wasNull() ? null : s;
        return new Row(rs.getObject("day", LocalDate.class), weight, steps);
    }
}
