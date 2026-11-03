package kz.calories;

import static org.assertj.core.api.Assertions.assertThat;

import kz.calories.agent.AgentReply;
import kz.calories.agent.AgentTools;
import kz.calories.bot.MessageRouter;
import kz.calories.dish.DishService;
import kz.calories.dish.DishVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(IntegrationTestBase.FakeLlmConfig.class)
class AgentFlowIT extends IntegrationTestBase {

    @Autowired
    ScriptedLlm llm;
    @Autowired
    MessageRouter router;
    @Autowired
    DishService dishes;

    private AgentReply say(long tg, String text) {
        return router.handleText(tg, "User" + tg, text).orElseThrow();
    }

    private long saveBorscht(long tg, double totalKcal, double cookedG, String text) {
        llm.script = t -> ((AgentTools) t).saveDish("Борщ", "RECIPE", totalKcal, totalKcal * 0.05, totalKcal / 30.0,
                totalKcal * 0.125, 20.0, cookedG, cookedG / 2, "варка", text, "MEDIUM", null);
        AgentReply r = say(tg, "вот рецепт борща");
        assertThat(r.cards()).hasSize(1);
        return r.cards().get(0).draftId();
    }

    private long userId(long tg) {
        return jdbc.sql("select id from app_user where telegram_id = :t").param("t", tg).query(Long.class).single();
    }

    @Test
    void newerVersionWinsAndOldEntriesKeepSnapshot() {
        long d1 = saveBorscht(1001, 1650, 3000, "версия 1");
        DishVersion v1 = dishes.confirmDraft(d1, userId(1001));

        llm.script = t -> {
            AgentTools tools = (AgentTools) t;
            return tools.logMeal(v1.id(), 300.0, null);
        };
        AgentReply first = say(1001, "съел 300 г борща");
        assertThat(first.text()).contains("Итого за сегодня");
        double kcalBefore = jdbc.sql("select kcal from meal_entry where dish_version_id = :v").param("v", v1.id())
                .query(Double.class).single();

        long d2 = saveBorscht(1001, 2200, 4000, "версия 2");
        DishVersion v2 = dishes.confirmDraft(d2, userId(1001));
        assertThat(dishes.latestActive("борщ").orElseThrow().id()).isEqualTo(v2.id());
        assertThat(dishes.search("борщ").get(0).version().id()).isEqualTo(v2.id());

        double kcalAfter = jdbc.sql("select kcal from meal_entry where dish_version_id = :v").param("v", v1.id())
                .query(Double.class).single();
        assertThat(kcalAfter).isEqualTo(kcalBefore);

        // деактивация новой версии возвращает актуальной предыдущую
        dishes.deactivate(v2.id());
        assertThat(dishes.latestActive("борщ").orElseThrow().id()).isEqualTo(v1.id());
    }

    @Test
    void similarNameFindsProduct() {
        llm.script = t -> ((AgentTools) t).saveDish("Творог Простоквашино 5%", "PRODUCT", 121.0, 16.0, 5.0, 3.0, null,
                null, null, null, "творог Простоквашино 5%: 121 ккал", "HIGH", null);
        long draft = say(1001, "творог Простоквашино 5%: 121 ккал, Б 16, Ж 5, У 3").cards().get(0).draftId();
        dishes.confirmDraft(draft, userId(1001));
        assertThat(dishes.search("творог простоквашино")).isNotEmpty();
    }

    @Test
    void recipeWithoutCookedWeightIsRejected() {
        llm.script = t -> ((AgentTools) t).saveDish("Плов", "RECIPE", 2000.0, 80.0, 90.0, 220.0, null, null, 1500.0,
                null, null, null, null);
        AgentReply r = say(1001, "рецепт плова");
        assertThat(r.cards()).isEmpty();
    }

    @Test
    void totalsAreSeparatePerUserAndForeignUserIsIgnored() {
        llm.script = t -> ((AgentTools) t).logEstimate("яблоко", 100.0, 52.0, 0.3, 0.2, 13.8, 2.4, "MEDIUM", null, null);
        AgentReply a = say(1003, "съел яблоко");
        assertThat(a.text()).contains("Итого за сегодня: 52 ккал");

        llm.script = t -> ((AgentTools) t).logEstimate("банан", 100.0, 89.0, 1.1, 0.3, 22.8, 2.6, "MEDIUM", null, null);
        AgentReply b = say(1002, "съел банан");
        assertThat(b.text()).contains("Итого за сегодня: 89 ккал");

        assertThat(router.handleText(5555, "Stranger", "привет")).isEmpty();
    }

    @Test
    void deactivationNeverDeletesRows() {
        llm.script = t -> ((AgentTools) t).logEstimate("печенье", 50.0, 480.0, 6.0, 20.0, 66.0, null, "LOW", null, null);
        say(1002, "съел печенье");
        long id = jdbc.sql("select max(id) from meal_entry where name = 'печенье'").query(Long.class).single();
        llm.script = t -> ((AgentTools) t).deactivateMealEntry(id);
        say(1002, "удали последнюю запись");
        assertThat(jdbc.sql("select active from meal_entry where id = :i").param("i", id).query(Boolean.class).single()).isFalse();
    }

    @Test
    void llmFailureDoesNotLoseMessage() {
        llm.script = t -> {
            throw new RuntimeException("429");
        };
        AgentReply r = say(1001, "привет, это сообщение нельзя терять");
        assertThat(r.text()).contains("сохранено");
        assertThat(jdbc.sql("select count(*) from chat_message where content like 'привет, это%'")
                .query(Long.class).single()).isEqualTo(1L);
    }
}
