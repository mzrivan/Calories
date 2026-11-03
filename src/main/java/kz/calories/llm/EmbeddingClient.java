package kz.calories.llm;

public interface EmbeddingClient {

    /** Вектор фиксированной размерности (app.llm.embedding-dimensions). */
    float[] embed(String text);
}
