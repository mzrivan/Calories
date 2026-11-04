package kz.calories;

import java.util.function.Function;
import kz.calories.llm.AgentLlm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Требует Docker (Testcontainers, Postgres 16). Без Docker тесты пропускаются. LLM — скриптовый мок. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.telegram.enabled=false",
        "app.allowed-telegram-ids=1001,1002,1003",
        "app.llm.retry-initial-delay-ms=1"
})
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16").asCompatibleSubstituteFor("postgres"))
            .withInitScript("test-init.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "calorie_app"); // приложение — под ролью без DELETE
        r.add("spring.datasource.password", () -> "app");
        r.add("spring.liquibase.url", PG::getJdbcUrl);
        r.add("spring.liquibase.user", PG::getUsername); // миграции — под владельцем
        r.add("spring.liquibase.password", PG::getPassword);
    }

    @Autowired
    protected JdbcClient jdbc;

    @TestConfiguration
    public static class FakeLlmConfig {
        @Bean
        @Primary
        public ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }

    }

    /** Мок-LLM: сценарий вызывает инструменты так, как это сделала бы модель. */
    public static class ScriptedLlm implements AgentLlm {
        public volatile Function<Object, String> script = tools -> "ok";
        /** Сценарий оценки незнакомых блюд; по умолчанию — типовая оценка. */
        public volatile Function<java.util.List<EstimateRequest>, java.util.List<DishEstimate>> estimator = reqs -> reqs.stream()
                .map(r -> new DishEstimate(r.name(), 100, 5, 4, 12, null, "MEDIUM", "тестовая оценка")).toList();
        public final java.util.concurrent.atomic.AtomicInteger chatCalls = new java.util.concurrent.atomic.AtomicInteger();
        public final java.util.concurrent.atomic.AtomicInteger estimateCalls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public LlmResult chat(String systemPrompt, java.util.List<ChatTurn> history, String userMessage, Object tools, int maxToolCalls) {
            chatCalls.incrementAndGet();
            return new LlmResult(script.apply(tools), 0, 0, 0);
        }

        @Override
        public java.util.List<DishEstimate> estimate(java.util.List<EstimateRequest> requests, String originalMessage) {
            estimateCalls.incrementAndGet();
            return estimator.apply(requests);
        }
    }
}
