package kz.calories.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.calories.config.AppProperties;
import org.junit.jupiter.api.Test;

/** Сборка клиента не должна требовать сети: иначе приложение не стартует (в т.ч. оценщик пачки блюд). */
class GeminiLlmWiringTest {

    @Test
    void buildsWithoutNetwork() {
        AppProperties props = new AppProperties(null, List.of(),
                new AppProperties.Llm("", "gemini-2.5-flash", 0.2, 6, 1, 1), null, null, null, null, null);
        assertThat(new GeminiLlm(props)).isNotNull();
    }
}
