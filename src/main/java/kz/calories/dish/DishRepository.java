package kz.calories.dish;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kz.calories.validation.Nutrition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DishRepository {

    private static final String COLS = """
            id, name, normalized_name, kind, kcal, protein_g, fat_g, carbs_g, fiber_g, cooked_weight_g,
            source_text, method, confidence, assumptions, created_by_user_id, created_at, active""";

    /** Самая свежая активная версия каждого блюда (по normalized_name). */
    private static final String LATEST = "select distinct on (normalized_name) " + COLS
            + " from dish_version where active order by normalized_name, created_at desc, id desc";

    public record Scored(DishVersion version, double score, String matchType) {}

    private final JdbcClient jdbc;

    public DishRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DraftPayload p, long userId) {
        Nutrition n = p.per100();
        return jdbc.sql("""
                insert into dish_version (name, normalized_name, kind, kcal, protein_g, fat_g, carbs_g, fiber_g,
                    cooked_weight_g, source_text, method, confidence, assumptions, created_by_user_id)
                values (:name, :norm, :kind, :kcal, :p, :f, :c, :fi,
                    :cw, :src, :method, :conf, :assum, :uid)
                returning id""")
                .param("name", p.name()).param("norm", Names.normalize(p.name()))
                .param("kind", p.kind().name())
                .param("kcal", n.kcal()).param("p", n.protein()).param("f", n.fat()).param("c", n.carbs())
                .param("fi", n.fiber()).param("cw", p.cookedWeightG()).param("src", p.sourceText())
                .param("method", p.method()).param("conf", p.confidence()).param("assum", p.assumptions())
                .param("uid", userId)
                .query(Long.class).single();
    }

    public Optional<DishVersion> findActiveById(long id) {
        return jdbc.sql("select " + COLS + " from dish_version where id = :id and active")
                .param("id", id).query(this::map).optional();
    }

    public Optional<DishVersion> latestActive(String normalizedName) {
        return jdbc.sql("select " + COLS + " from dish_version where active and normalized_name = :n "
                        + "order by created_at desc, id desc limit 1")
                .param("n", normalizedName).query(this::map).optional();
    }

    /** Все версии (активные и нет) блюда с тем же названием, свежие первыми. */
    public List<DishVersion> versionsOf(String normalizedName) {
        return jdbc.sql("select " + COLS + " from dish_version where normalized_name = :n order by created_at desc, id desc")
                .param("n", normalizedName).query(this::map).list();
    }

    public List<DishVersion> listLatest(String likeFilter, int limit) {
        return jdbc.sql("select * from (" + LATEST + ") l where :f = '' or normalized_name like :like order by name limit :lim")
                .param("f", likeFilter == null ? "" : likeFilter)
                .param("like", "%" + (likeFilter == null ? "" : likeFilter) + "%")
                .param("lim", limit).query(this::map).list();
    }

    public List<Scored> searchExact(String normalizedName) {
        return jdbc.sql("select " + COLS + " from (" + LATEST + ") l where normalized_name = :n")
                .param("n", normalizedName)
                .query((rs, i) -> new Scored(map(rs, i), 1.0, "exact")).list();
    }

    public List<Scored> searchTrgm(String normalizedQuery, double threshold, int limit) {
        return jdbc.sql("""
                select * from (
                    select l.*, greatest(similarity(normalized_name, :q), word_similarity(:q, normalized_name)) as score
                    from (""" + LATEST + """
                    ) l) s
                where score >= :t order by score desc limit :lim""")
                .param("q", normalizedQuery).param("t", threshold).param("lim", limit)
                .query((rs, i) -> new Scored(map(rs, i), rs.getDouble("score"), "trgm")).list();
    }

    public Optional<String> aliasTarget(String aliasNormalized) {
        return jdbc.sql("select dish_normalized_name from dish_alias where alias_normalized = :a")
                .param("a", aliasNormalized).query(String.class).optional();
    }

    public void saveAlias(String aliasNormalized, String dishNormalizedName) {
        jdbc.sql("insert into dish_alias (alias_normalized, dish_normalized_name) values (:a, :d) "
                        + "on conflict (alias_normalized) do nothing")
                .param("a", aliasNormalized).param("d", dishNormalizedName).update();
    }

    public boolean deactivate(long id) {
        return jdbc.sql("update dish_version set active = false, deactivated_at = now() where id = :id and active")
                .param("id", id).update() > 0;
    }

    private DishVersion map(ResultSet rs, int row) throws SQLException {
        Nutrition n = new Nutrition(rs.getDouble("kcal"), rs.getDouble("protein_g"), rs.getDouble("fat_g"),
                rs.getDouble("carbs_g"), nd(rs, "fiber_g"));
        long createdBy = rs.getLong("created_by_user_id");
        Long createdByUserId = rs.wasNull() ? null : createdBy;
        return new DishVersion(rs.getLong("id"), rs.getString("name"), rs.getString("normalized_name"),
                DishVersion.Kind.valueOf(rs.getString("kind")), n, nd(rs, "cooked_weight_g"),
                rs.getString("source_text"), rs.getString("method"), rs.getString("confidence"),
                rs.getString("assumptions"), createdByUserId,
                rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("active"));
    }

    private static Double nd(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
