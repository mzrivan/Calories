package kz.calories.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Optional;
import kz.calories.config.AppProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Whitelist: telegram_id должен быть в конфиге, а пользователь в БД — active. */
@Service
public class UserService {

    private static final String COLS = "id, telegram_id, name, timezone, goal_kcal, goal_protein, goal_fat, goal_carbs, goal_fiber";

    private final JdbcClient jdbc;
    private final AppProperties props;

    public UserService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Возвращает пользователя или empty для чужого/неактивного telegram_id. Первое сообщение разрешённого создаёт запись. */
    public Optional<AppUser> resolve(long telegramId, String firstName) {
        if (!props.allowedTelegramIds().contains(telegramId)) {
            return Optional.empty();
        }
        String name = (firstName == null || firstName.isBlank()) ? "user-" + telegramId : firstName;
        jdbc.sql("insert into app_user (telegram_id, name) values (:tg, :name) on conflict (telegram_id) do nothing")
                .param("tg", telegramId).param("name", name).update();
        return jdbc.sql("select " + COLS + " from app_user where telegram_id = :tg and active")
                .param("tg", telegramId).query(this::map).optional();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.sql("select " + COLS + " from app_user where id = :id and active")
                .param("id", id).query(this::map).optional();
    }

    public void setGoals(long userId, AppUser.Goals g) {
        jdbc.sql("""
                update app_user set
                  goal_kcal = coalesce(:kcal, goal_kcal), goal_protein = coalesce(:p, goal_protein),
                  goal_fat = coalesce(:f, goal_fat), goal_carbs = coalesce(:c, goal_carbs),
                  goal_fiber = coalesce(:fi, goal_fiber)
                where id = :id and active
                """)
                .param("kcal", g.kcal()).param("p", g.protein()).param("f", g.fat())
                .param("c", g.carbs()).param("fi", g.fiber()).param("id", userId).update();
    }

    private AppUser map(ResultSet rs, int row) throws SQLException {
        AppUser.Goals goals = new AppUser.Goals(d(rs, "goal_kcal"), d(rs, "goal_protein"), d(rs, "goal_fat"),
                d(rs, "goal_carbs"), d(rs, "goal_fiber"));
        return new AppUser(rs.getLong("id"), rs.getLong("telegram_id"), rs.getString("name"),
                ZoneId.of(rs.getString("timezone")), goals);
    }

    static Double d(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
