package kz.calories.llm;

import java.util.List;

/**
 * Свой интерфейс над LLM-провайдером. Два режима:
 * 1) chat — свободный диалог с инструментами (@Tool): рецепты, правки, нестандартные формулировки;
 * 2) estimate — один запрос на пачку незнакомых блюд, структурированный ответ без инструментов.
 */
public interface AgentLlm {

    LlmResult chat(String systemPrompt, List<ChatTurn> history, String userMessage, Object tools, int maxToolCalls);

    /** Оценка КБЖУ на 100 г для списка блюд; результат в том же порядке и той же длины, что и запрос. */
    List<DishEstimate> estimate(List<EstimateRequest> requests, String originalMessage);

    record ChatTurn(boolean fromUser, String content) {}

    record LlmResult(String text, int inputTokens, int outputTokens, int toolCalls) {}

    /** Что оценить: название и либо вес порции, либо съеденные ккал (для контекста). */
    record EstimateRequest(String name, Double grams, Double kcal) {}

    /** Значения на 100 г готового продукта/блюда в том виде, в каком его едят. */
    record DishEstimate(String name, double kcalPer100g, double proteinPer100g, double fatPer100g,
                        double carbsPer100g, Double fiberPer100g, String confidence, String assumptions) {}
}
