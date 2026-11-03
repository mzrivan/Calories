package kz.calories.llm;

import java.util.List;

/**
 * Свой интерфейс над LLM-провайдером. Реализация получает системный промпт, историю диалога,
 * сообщение пользователя и объект с инструментами (методы с аннотацией @Tool), и сама ведёт tool calling.
 */
public interface AgentLlm {

    LlmResult chat(String systemPrompt, List<ChatTurn> history, String userMessage, Object tools, int maxToolCalls);

    record ChatTurn(boolean fromUser, String content) {}

    record LlmResult(String text, int inputTokens, int outputTokens, int toolCalls) {}
}
