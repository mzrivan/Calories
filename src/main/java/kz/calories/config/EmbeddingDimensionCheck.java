package kz.calories.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Приложение не стартует, если размерность в конфиге и в миграции (vector(N)) различаются. */
@Component
public class EmbeddingDimensionCheck implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final AppProperties props;

    public EmbeddingDimensionCheck(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer dbDim = jdbc.sql("""
                select atttypmod from pg_attribute
                where attrelid = 'dish_version'::regclass and attname = 'embedding'
                """).query(Integer.class).single();
        int configured = props.llm().embeddingDimensions();
        if (dbDim == null || dbDim != configured) {
            throw new IllegalStateException("Размерность эмбеддингов: в БД " + dbDim
                    + ", в конфиге (EMBEDDING_DIMENSIONS) " + configured + ". Приведите их в соответствие.");
        }
    }
}
