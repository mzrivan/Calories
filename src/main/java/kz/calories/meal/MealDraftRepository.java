package kz.calories.meal;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Черновики пачки блюд («распознано примерно — подтвердите»), jsonb-payload. */
@Repository
public class MealDraftRepository {

    public record Row(long id, long userId, String payloadJson, String status, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbc;

    public MealDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Новый черновик; прежние ожидающие черновики пользователя отменяются. */
    public long create(long userId, String payloadJson, int ttlHours) {
        jdbc.sql("update meal_draft set status = 'CANCELLED' where user_id = :u and status = 'PENDING'")
                .param("u", userId).update();
        return jdbc.sql("""
                insert into meal_draft (user_id, payload, expires_at)
                values (:u, cast(:p as jsonb), now() + make_interval(hours => :h)) returning id""")
                .param("u", userId).param("p", payloadJson).param("h", ttlHours)
                .query(Long.class).single();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("select id, user_id, payload::text as payload, status, expires_at from meal_draft where id = :id")
                .param("id", id)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("payload"),
                        rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    /** Переводит из PENDING в новый статус; false, если черновик уже обработан. */
    public boolean transition(long id, String newStatus) {
        return jdbc.sql("update meal_draft set status = :s where id = :id and status = 'PENDING'")
                .param("s", newStatus).param("id", id).update() > 0;
    }
}
