package kz.calories.llm;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import kz.calories.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class GeminiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel model;
    private final int dimensions;

    public GeminiEmbeddingClient(AppProperties props) {
        AppProperties.Llm cfg = props.llm();
        this.dimensions = cfg.embeddingDimensions();
        this.model = GoogleAiEmbeddingModel.builder()
                .apiKey(cfg.apiKey().isBlank() ? "missing-key" : cfg.apiKey())
                .modelName(cfg.embeddingModel())
                .outputDimensionality(cfg.embeddingDimensions())
                .build();
    }

    @Override
    public float[] embed(String text) {
        float[] v = model.embed(text).content().vector();
        if (v.length != dimensions) {
            throw new IllegalStateException("Модель эмбеддингов вернула " + v.length + " измерений, ожидалось " + dimensions);
        }
        return v;
    }
}
