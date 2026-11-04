package kz.calories.energy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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

    private Row map(ResultSet rs, int i) throws SQLException {
        double w = rs.getDouble("weight_kg");
        Double weight = rs.wasNull() ? null : w;
        int s = rs.getInt("steps");
        Integer steps = rs.wasNull() ? null : s;
        return new Row(rs.getObject("day", LocalDate.class), weight, steps);
    }
}
