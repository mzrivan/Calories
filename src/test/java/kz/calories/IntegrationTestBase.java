package kz.calories;

import java.util.Random;
import java.util.function.Function;
import kz.calories.llm.AgentLlm;
import kz.calories.llm.EmbeddingClient;
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

/** Требует Docker (Testcontainers, Postgres 16 + pgvector). Без Docker тесты пропускаются. LLM — скриптовый мок. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.telegram.enabled=false",
        "app.allowed-telegram-ids=1001,1002,1003",
        "app.llm.retry-initial-delay-ms=1"
})
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
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

        @Bean
        @Primary
        public EmbeddingClient fakeEmbeddings() {
            return text -> {
                Random rnd = new Random(text.hashCode());
                float[] v = new float[768];
                for (int i = 0; i < v.length; i++) {
                    v[i] = rnd.nextFloat() - 0.5f;
                }
                return v;
            };
        }
    }

    /** Мок-LLM: сценарий вызывает инструменты так, как это сделала бы модель. */
    public static class ScriptedLlm implements AgentLlm {
        public volatile Function<Object, String> script = tools -> "ok";

        @Override
        public LlmResult chat(String systemPrompt, java.util.List<ChatTurn> history, String userMessage, Object tools, int maxToolCalls) {
            return new LlmResult(script.apply(tools), 0, 0, 0);
        }
    }
}
