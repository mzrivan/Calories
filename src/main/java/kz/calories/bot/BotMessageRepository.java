package kz.calories.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import kz.calories.agent.RecordRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BotMessageRepository {
    public record Stored(long id, String text, List<RecordRef> records) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public BotMessageRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(long userId, long chatId, int messageId, String text, List<RecordRef> records) {
        jdbc.sql("""
                insert into bot_message (user_id, chat_id, message_id, content, records_json)
                values (:u, :c, :m, :t, cast(:r as jsonb))
                on conflict (user_id, chat_id, message_id)
                do update set content = excluded.content, records_json = excluded.records_json""")
                .param("u", userId).param("c", chatId).param("m", messageId)
                .param("t", normalize(text)).param("r", encode(records)).update();
    }

    public Optional<Stored> find(long userId, long chatId, int messageId) {
        return query(jdbc.sql("""
                select id, content, records_json::text as records from bot_message
                where user_id = :u and chat_id = :c and message_id = :m""")
                .param("u", userId).param("c", chatId).param("m", messageId));
    }

    /** У пересылки из личного чата Telegram нет исходного message_id. Не угадываем при одинаковых текстах. */
    public Optional<Stored> findForward(long userId, String text) {
        List<Stored> found = jdbc.sql("""
                select distinct on (records_json) id, content, records_json::text as records from bot_message
                where user_id = :u and md5(content) = md5(:t) and content = :t order by records_json, id desc limit 2""")
                .param("u", userId).param("t", normalize(text))
                .query((rs, i) -> new Stored(rs.getLong("id"), rs.getString("content"), decode(rs.getString("records")))).list();
        if (found.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(found.get(0));
    }

    public void select(long userId, long chatId, long storedId) {
        jdbc.sql("""
                insert into bot_message_selection (user_id, chat_id, bot_message_id, expires_at)
                values (:u, :c, :id, now() + interval '15 minutes')
                on conflict (user_id, chat_id) do update
                set bot_message_id = excluded.bot_message_id, expires_at = excluded.expires_at""")
                .param("u", userId).param("c", chatId).param("id", storedId).update();
    }

    public Optional<Stored> selected(long userId, long chatId) {
        return query(jdbc.sql("""
                select id, content, records_json::text as records from bot_message
                where user_id = :u and id = (select bot_message_id from bot_message_selection
                where user_id = :u and chat_id = :c and expires_at > now())""")
                .param("u", userId).param("c", chatId));
    }

    public void clear(long userId, long chatId) {
        jdbc.sql("update bot_message_selection set expires_at = now() where user_id = :u and chat_id = :c")
                .param("u", userId).param("c", chatId).update();
    }

    private Optional<Stored> query(JdbcClient.StatementSpec statement) {
        return statement
                .query((rs, i) -> new Stored(rs.getLong("id"), rs.getString("content"), decode(rs.getString("records"))))
                .optional();
    }

    /** Telegram обрезает пробелы и переводы строк по краям, поэтому сравниваем текст в том же виде. */
    private static String normalize(String text) {
        return text == null ? "" : text.strip();
    }

    private String encode(List<RecordRef> refs) {
        try {
            return mapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сохранить ссылки на записи", e);
        }
    }

    private List<RecordRef> decode(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<RecordRef>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось прочитать ссылки на записи", e);
        }
    }
}
