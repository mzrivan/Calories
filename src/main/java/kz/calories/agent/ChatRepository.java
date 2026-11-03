package kz.calories.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kz.calories.llm.AgentLlm;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {

    private final JdbcClient jdbc;

    public ChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(long userId, boolean fromUser, String content) {
        jdbc.sql("insert into chat_message (user_id, role, content) values (:u, :r, :c)")
                .param("u", userId).param("r", fromUser ? "USER" : "ASSISTANT").param("c", content).update();
    }

    /** Последние N сообщений за последние X минут, в хронологическом порядке. */
    public List<AgentLlm.ChatTurn> recent(long userId, int limit, int minutes) {
        List<AgentLlm.ChatTurn> turns = new ArrayList<>(jdbc.sql("""
                select role, content from chat_message
                where user_id = :u and created_at > now() - make_interval(mins => :m)
                order by created_at desc, id desc limit :l""")
                .param("u", userId).param("m", minutes).param("l", limit)
                .query((rs, i) -> new AgentLlm.ChatTurn("USER".equals(rs.getString("role")), rs.getString("content")))
                .list());
        Collections.reverse(turns);
        return turns;
    }
}
