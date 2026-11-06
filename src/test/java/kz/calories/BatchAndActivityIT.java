package kz.calories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.calories.agent.AgentReply;
import kz.calories.bot.MessageRouter;
import kz.calories.bot.MessageActions;
import kz.calories.bot.BotMessageRepository;
import kz.calories.meal.MealBatchService;
import kz.calories.llm.AgentLlm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Основной сценарий: список «блюдо + вес» без LLM, подтверждение примерных совпадений, оценка пачки, расход калорий. */
@Import(IntegrationTestBase.FakeLlmConfig.class)
class BatchAndActivityIT extends IntegrationTestBase {

    @Autowired
    ScriptedLlm llm;
    @Autowired
    MessageRouter router;
    @Autowired
    MessageActions actions;
    @Autowired
    BotMessageRepository messages;
    @Autowired
    MealBatchService batches;
    @Autowired
    kz.calories.user.UserService users;

    @BeforeEach
    void resetCounters() {
        llm.chatCalls.set(0);
        llm.estimateCalls.set(0);
    }

    private AgentReply say(long tg, String text) {
        return router.handleText(tg, "User" + tg, text).orElseThrow();
    }

    private long entries(long tg) {
        return jdbc.sql("select count(*) from meal_entry e join app_user u on u.id = e.user_id where u.telegram_id = :t and e.active")
                .param("t", tg).query(Long.class).single();
    }

    @Test
    void exactListIsWrittenInOneGoWithoutLlm() {
        long before = entries(1001);
        AgentReply r = say(1001, "Хлеб 13, молоко 60, мандарин 24");
        assertThat(r.cards()).isEmpty();
        assertThat(r.text()).contains("Записано").contains("Итого за сегодня");
        assertThat(r.undoBatchId()).isNotNull();
        assertThat(entries(1001)).isEqualTo(before + 3);
        assertThat(llm.chatCalls.get()).isZero();
        assertThat(llm.estimateCalls.get()).isZero();

        // «Отменить» деактивирует всю пачку, не удаляя строк
        var result = router.handleCallback(1001, "User1001", "undo:" + r.undoBatchId()).orElseThrow();
        assertThat(result.toast()).isEqualTo("Отменено");
        assertThat(entries(1001)).isEqualTo(before);
    }

    @Test
    void forwardedMessageDeactivatesOnlyItsOwnRecordsAndSelectionIsConsumed() {
        AgentReply first = say(1001, "Хлеб 17, молоко 23");
        AgentReply other = say(1001, "Хлеб 19");
        long before = entries(1001);
        actions.remember(1001, "User1001", 1001, 2001, first.text(), first.records());
        assertThat(actions.reference(1001, "User1001", 1001, null, first.text(), null).orElseThrow().text())
                .contains("Напишите");
        assertThat(actions.pending(1001, "User1001", 1001, "удали").orElseThrow().text()).contains("деактивирована");
        assertThat(entries(1001)).isEqualTo(before - 2);
        var user = users.resolve(1001, "User1001").orElseThrow();
        assertThat(jdbc.sql("select active from meal_entry where id = :id")
                .param("id", other.records().getFirst().id()).query(Boolean.class).single()).isTrue();
        for (var ref : first.records()) {
            assertThat(jdbc.sql("select not active and deactivated_at is not null from meal_entry where id = :id")
                    .param("id", ref.id()).query(Boolean.class).single()).isTrue();
        }
        assertThat(messages.selected(user.id(), 1001)).isEmpty();
        assertThat(messages.findForward(users.resolve(1002, "User1002").orElseThrow().id(), first.text())).isEmpty();
        assertThat(llm.chatCalls.get()).isZero();
    }

    @Test
    void duplicateForwardTextsAreNotGuessedEvenWhenTwoLatestCopiesMatch() {
        AgentReply first = say(1001, "Хлеб 21");
        AgentReply second = say(1001, "Хлеб 22");
        String text = "Одинаковый текст подтверждения";
        actions.remember(1001, "User1001", 1001, 2101, text, first.records());
        actions.remember(1001, "User1001", 1001, 2102, text, second.records());
        actions.remember(1001, "User1001", 1001, 2103, text, second.records());
        long uid = users.resolve(1001, "User1001").orElseThrow().id();
        assertThat(messages.findForward(uid, text)).isEmpty();
        assertThat(messages.find(uid, 1001, 2101).orElseThrow().records()).isEqualTo(first.records());
    }

    @Test
    void editingPendingMealDraftRequiresConfirmationAndPreservesDate() {
        AgentReply original = say(1001, "Неведомая еда для правки 73");
        var user = users.resolve(1001, "User1001").orElseThrow();
        long before = entries(1001);
        AgentReply replacement = batches.replaceDraft(user, original.cards().getFirst().draftId(), "Хлеб 35");
        assertThat(replacement.cards()).hasSize(1);
        assertThat(entries(1001)).isEqualTo(before);
        var old = router.handleCallback(1001, "User1001", "mconfirm:" + original.cards().getFirst().draftId()).orElseThrow();
        assertThat(old.newText()).isNull();
        var confirmed = router.handleCallback(1001, "User1001", "mconfirm:" + replacement.cards().getFirst().draftId()).orElseThrow();
        assertThat(confirmed.records()).hasSize(1);
        assertThat(entries(1001)).isEqualTo(before + 1);
        assertThat(jdbc.sql("""
                select eaten_at = (select (payload->>'eatenAt')::timestamptz from meal_draft where id = :draft)
                from meal_entry where id = :id""")
                .param("draft", original.cards().getFirst().draftId())
                .param("id", confirmed.records().getFirst().id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void approximateMatchNeedsConfirmationAndLearnsAlias() {
        long before = entries(1002);
        AgentReply r = say(1002, "Олодьи 62, йогурт 42");
        assertThat(r.cards()).hasSize(1);
        assertThat(entries(1002)).isEqualTo(before); // до подтверждения ничего не записано
        assertThat(llm.estimateCalls.get()).isZero();

        var done = router.handleCallback(1002, "User1002", "mconfirm:" + r.cards().get(0).draftId()).orElseThrow();
        assertThat(done.newText()).contains("Записано");
        assertThat(entries(1002)).isEqualTo(before + 2);
        assertThat(jdbc.sql("select count(*) from dish_alias where alias_normalized = 'олодьи'").query(Long.class).single()).isEqualTo(1L);

        // повторное нажатие не дублирует записи
        var again = router.handleCallback(1002, "User1002", "mconfirm:" + r.cards().get(0).draftId()).orElseThrow();
        assertThat(again.newText()).isNull();
        assertThat(entries(1002)).isEqualTo(before + 2);

        // теперь «олодьи» — точный алиас: пишется сразу
        AgentReply next = say(1002, "Олодьи 30");
        assertThat(next.cards()).isEmpty();
        assertThat(next.text()).contains("Записано");
    }

    @Test
    void unknownDishesGoToLlmInOneBatchAndBecomeExactAfterConfirm() {
        AgentReply r = say(1003, "Заморский салат 150, хлеб 20, редкая штука 80");
        assertThat(llm.estimateCalls.get()).isEqualTo(1); // одна пачка на оба незнакомых блюда
        assertThat(llm.chatCalls.get()).isZero();
        assertThat(r.cards()).hasSize(1);
        assertThat(r.cards().get(0).text()).contains("оценка ИИ");

        router.handleCallback(1003, "User1003", "mconfirm:" + r.cards().get(0).draftId()).orElseThrow();
        assertThat(jdbc.sql("select count(*) from dish_version where normalized_name = 'заморский салат' and method = 'LLM'")
                .query(Long.class).single()).isEqualTo(1L);

        AgentReply next = say(1003, "Заморский салат 100");
        assertThat(next.cards()).isEmpty();
        assertThat(llm.estimateCalls.get()).isEqualTo(1); // повторно в LLM не ходили
    }

    @Test
    void llmFailureOnEstimateWritesNothing() {
        llm.estimator = reqs -> {
            throw new RuntimeException("503");
        };
        long before = entries(1001);
        try {
            AgentReply r = say(1001, "Хлеб 10, неведомое блюдо 100");
            assertThat(r.text()).contains("Ничего не записано");
            assertThat(entries(1001)).isEqualTo(before);
        } finally {
            llm.estimator = reqs -> reqs.stream()
                    .map(x -> new AgentLlm.DishEstimate(x.name(), 100, 5, 4, 12, null, "MEDIUM", "тест")).toList();
        }
    }

    @Test
    void unparsedMessageFallsBackToAgent() {
        llm.script = t -> "ответ агента";
        AgentReply r = say(1001, "83 творог 0.1 йогурт 40 1.5");
        assertThat(llm.chatCalls.get()).isEqualTo(1);
        assertThat(r.text()).contains("ответ агента");
    }

    @Test
    void userDeletesEntriesByNumberAndId() {
        long tg = 1004;
        say(tg, "Хлеб 13, молоко 60, мандарин 24");
        assertThat(entries(tg)).isEqualTo(3);
        String today = say(tg, "/today").text();
        assertThat(today).contains("1. ").contains("2. ").contains("3. ").contains("(id ");

        // по номеру: запись остаётся в БД, но неактивна
        AgentReply del = say(tg, "/del 2");
        assertThat(del.text()).contains("Убрано");
        assertThat(entries(tg)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from meal_entry e join app_user u on u.id = e.user_id "
                + "where u.telegram_id = :t and not e.active and e.deactivated_at is not null").param("t", tg)
                .query(Long.class).single()).isEqualTo(1L);

        // по id
        long id = jdbc.sql("select e.id from meal_entry e join app_user u on u.id = e.user_id "
                + "where u.telegram_id = :t and e.active order by e.id limit 1").param("t", tg).query(Long.class).single();
        assertThat(say(tg, "/delmeal " + id).text()).contains("Убрано");
        assertThat(entries(tg)).isEqualTo(1);
        assertThat(say(tg, "/delmeal " + id).text()).contains("не найдена");

        // несуществующий номер и чужие/неверные аргументы
        assertThat(say(tg, "/del 9").text()).contains("нет записи №9");
        assertThat(say(tg, "/del abc").text()).contains("Формат");
        assertThat(say(tg, "/del 1 когда-нибудь").text()).contains("Не поняла дату");
        assertThat(say(tg, "/delact 999999").text()).contains("не найдена");
        assertThat(llm.chatCalls.get()).isZero();
    }

    @Test
    void parseDay() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 3, 10);
        assertThat(MessageRouter.parseDay("вчера", today)).contains(today.minusDays(1));
        assertThat(MessageRouter.parseDay("2026-03-01", today)).contains(java.time.LocalDate.of(2026, 3, 1));
        assertThat(MessageRouter.parseDay("01.03", today)).contains(java.time.LocalDate.of(2026, 3, 1));
        assertThat(MessageRouter.parseDay("31.12", today)).contains(java.time.LocalDate.of(2025, 12, 31));
        assertThat(MessageRouter.parseDay("30.02", today)).isEmpty();
    }

    @Test
    void activityAndBalance() {
        long tg = 1003;
        assertThat(say(tg, "баланс").text()).contains("не хватает данных");

        assertThat(say(tg, "рост 178, возраст 35, пол м").text()).contains("Профиль").contains("рост 178");
        AgentReply w = say(tg, "вес 82,5, шагов 9500");
        assertThat(w.text()).contains("Сохранено").contains("82.5").contains("Расход:").contains("Баланс");
        assertThat(llm.chatCalls.get()).isZero();

        // данные за день обновляются, строка одна
        say(tg, "шагов 12000");
        long uid = jdbc.sql("select id from app_user where telegram_id = :t").param("t", tg).query(Long.class).single();
        List<Integer> steps = jdbc.sql("select steps from daily_activity where user_id = :u").param("u", uid).query(Integer.class).list();
        assertThat(steps).containsExactly(12000);
        Double total = jdbc.sql("select total_kcal from daily_activity where user_id = :u").param("u", uid).query(Double.class).single();
        // BMR = 10*82.5 + 6.25*178 - 5*35 + 5 ≈ 1767.5; ×1.1 + шаги > 1943
        assertThat(total).isGreaterThan(1943.0);

        assertThat(say(tg, "баланс").text()).contains("Расход:").contains("Баланс");
        assertThat(say(tg, "/today").text()).contains("Итого за сегодня");
    }
}
