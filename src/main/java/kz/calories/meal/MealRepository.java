package kz.calories.meal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kz.calories.validation.Nutrition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MealRepository {

    private static final String COLS = "id, user_id, eaten_at, name, dish_version_id, grams, kcal, protein_g, fat_g, carbs_g, "
            + "fiber_g, confidence, assumptions, meal_type";

    private final JdbcClient jdbc;

    public MealRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, OffsetDateTime eatenAt, String name, Long dishVersionId, double grams,
                       Nutrition n, String confidence, String assumptions, MealType mealType) {
        return insert(userId, eatenAt, name, dishVersionId, grams, n, confidence, assumptions, null, mealType);
    }

    public long insert(long userId, OffsetDateTime eatenAt, String name, Long dishVersionId, double grams,
                       Nutrition n, String confidence, String assumptions, UUID batchId, MealType mealType) {
        return jdbc.sql("""
                insert into meal_entry (user_id, eaten_at, name, dish_version_id, grams, kcal, protein_g, fat_g, carbs_g,
                    fiber_g, confidence, assumptions, batch_id, meal_type)
                values (:u, :at, :name, :dv, :g, :kcal, :p, :f, :c, :fi, :conf, :assum, :batch, :mt) returning id""")
                .param("u", userId).param("at", eatenAt).param("name", name).param("dv", dishVersionId)
                .param("g", grams).param("kcal", n.kcal()).param("p", n.protein()).param("f", n.fat())
                .param("c", n.carbs()).param("fi", n.fiber()).param("conf", confidence).param("assum", assumptions).param("batch", batchId)
                .param("mt", (mealType == null ? MealType.OTHER : mealType).name())
                .query(Long.class).single();
    }

    /** Только своя активная запись. */
    public Optional<MealEntry> find(long userId, long id) {
        return jdbc.sql("select " + COLS + " from meal_entry where id = :id and user_id = :u and active")
                .param("id", id).param("u", userId).query(this::map).optional();
    }

    public boolean update(long userId, long id, OffsetDateTime eatenAt, double grams, Nutrition n, MealType mealType) {
        return jdbc.sql("""
                update meal_entry set eaten_at = :at, grams = :g, kcal = :kcal, protein_g = :p, fat_g = :f,
                    carbs_g = :c, fiber_g = :fi, meal_type = :mt
                where id = :id and user_id = :u and active""")
                .param("at", eatenAt).param("g", grams).param("kcal", n.kcal()).param("p", n.protein())
                .param("f", n.fat()).param("c", n.carbs()).param("fi", n.fiber()).param("mt", mealType.name())
                .param("id", id).param("u", userId).update() > 0;
    }

    public boolean deactivate(long userId, long id) {
        return jdbc.sql("update meal_entry set active = false, deactivated_at = now() where id = :id and user_id = :u and active")
                .param("id", id).param("u", userId).update() > 0;
    }

    /** Деактивирует все активные записи пачки пользователя; возвращает их число. */
    public int deactivateBatch(long userId, UUID batchId) {
        return jdbc.sql("update meal_entry set active = false, deactivated_at = now() "
                        + "where batch_id = :b and user_id = :u and active")
                .param("b", batchId).param("u", userId).update();
    }

    public Optional<MealEntry> lastActive(long userId) {
        return jdbc.sql("select " + COLS + " from meal_entry where user_id = :u and active order by created_at desc, id desc limit 1")
                .param("u", userId).query(this::map).optional();
    }

    public List<MealEntry> between(long userId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("select " + COLS + " from meal_entry where user_id = :u and active and eaten_at >= :f and eaten_at < :t "
                        + "order by eaten_at, id")
                .param("u", userId).param("f", from).param("t", to).query(this::map).list();
    }

    public DayTotals totals(long userId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                select coalesce(sum(kcal), 0) kcal, coalesce(sum(protein_g), 0) p, coalesce(sum(fat_g), 0) f,
                       coalesce(sum(carbs_g), 0) c, sum(fiber_g) fi, count(*) cnt
                from meal_entry where user_id = :u and active and eaten_at >= :f and eaten_at < :t""")
                .param("u", userId).param("f", from).param("t", to)
                .query((rs, i) -> {
                    double fi = rs.getDouble("fi");
                    Double fiber = rs.wasNull() ? null : fi;
                    return new DayTotals(new Nutrition(rs.getDouble("kcal"), rs.getDouble("p"), rs.getDouble("f"),
                            rs.getDouble("c"), fiber), rs.getInt("cnt"));
                }).single();
    }

    private MealEntry map(ResultSet rs, int row) throws SQLException {
        double fi = rs.getDouble("fiber_g");
        Double fiber = rs.wasNull() ? null : fi;
        long dv = rs.getLong("dish_version_id");
        Long dishVersionId = rs.wasNull() ? null : dv;
        return new MealEntry(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("eaten_at", OffsetDateTime.class),
                rs.getString("name"), dishVersionId, rs.getDouble("grams"),
                new Nutrition(rs.getDouble("kcal"), rs.getDouble("protein_g"), rs.getDouble("fat_g"),
                        rs.getDouble("carbs_g"), fiber),
                rs.getString("confidence"), rs.getString("assumptions"), MealType.valueOf(rs.getString("meal_type")));
    }
}
