package kz.calories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(IntegrationTestBase.FakeLlmConfig.class)
class SchemaProtectionIT extends IntegrationTestBase {

    private void seed() {
        jdbc.sql("insert into app_user (telegram_id, name) values (9001, 'seed') on conflict do nothing").update();
        jdbc.sql("""
                insert into meal_entry (user_id, eaten_at, name, grams, kcal, protein_g, fat_g, carbs_g)
                select id, now(), 'x', 100, 100, 1, 1, 1 from app_user where telegram_id = 9001""").update();
    }

    @Test
    void appRoleCannotDelete() throws SQLException {
        seed();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "calorie_app", "app");
             Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("delete from meal_entry")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void ownerDeleteIsBlockedByTrigger() throws SQLException {
        seed();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            for (String table : new String[]{"meal_entry", "dish_version", "app_user", "chat_message"}) {
                // триггер срабатывает на каждую строку; для пустой таблицы проверка не имеет смысла
                if (table.equals("dish_version") || table.equals("chat_message")) {
                    continue;
                }
                assertThatThrownBy(() -> st.executeUpdate("delete from " + table)).isInstanceOf(SQLException.class)
                        .hasMessageContaining("Physical DELETE is forbidden");
            }
        }
    }

    @Test
    void dishVersionIsImmutableExceptDeactivation() throws SQLException {
        seed();
        jdbc.sql("""
                insert into dish_version (name, normalized_name, kind, kcal, protein_g, fat_g, carbs_g, created_by_user_id)
                select 'Борщ', 'борщ', 'RECIPE', 50, 3, 2, 5, id from app_user where telegram_id = 9001""").update();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("update dish_version set kcal = 1")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
            assertThat(st.executeUpdate("update dish_version set active = false, deactivated_at = now()")).isPositive();
        }
    }
}
