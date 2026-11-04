package kz.calories.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.Result;
import java.util.List;
import kz.calories.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Реализация AgentLlm на LangChain4j + Google Gemini. */
@Component
public class GeminiLlm implements AgentLlm {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlm.class);

    interface Assistant {
        Result<String> chat(String message);
    }

    /** Ответ оценщика: обёртка нужна для структурированного вывода (корень — объект). */
    public record Estimates(List<DishEstimate> items) {}

    interface Estimator {
        @SystemMessage(ESTIMATE_PROMPT)
        Estimates estimate(@dev.langchain4j.service.UserMessage String request);
    }

    static final String ESTIMATE_PROMPT = """
            Ты — справочник по питанию. Для каждой позиции списка оцени КБЖУ и клетчатку НА 100 г продукта или блюда
            в том виде, в каком его едят (готовое, а не сырое). Верни элементы items В ТОМ ЖЕ ПОРЯДКЕ и ТОЙ ЖЕ ДЛИНЫ, что список.
            name — название как в запросе. Углеводы включают клетчатку; ккал ≈ 4·Б + 9·Ж + 4·У.
            Клетчатку (fiberPer100g) указывай только если уверен, иначе null. confidence: LOW, MEDIUM или HIGH.
            assumptions — коротко (что принято за состав, способ приготовления), на русском. Домашнее блюдо без рецепта
            оценивай как типичное и ставь confidence LOW. Ничего, кроме данных, не пиши.
            """;

    private final ChatModel model;
    private final Estimator estimator;
    private final String modelName;

    public GeminiLlm(AppProperties props) {
        AppProperties.Llm cfg = props.llm();
        this.modelName = cfg.model();
        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(cfg.apiKey().isBlank() ? "missing-key" : cfg.apiKey())
                .modelName(cfg.model())
                .temperature(cfg.temperature())
                // Gemini 3: вызовы инструментов требуют thought_signature — сохраняем его из ответа и отправляем обратно
                .returnThinking(true)
                .sendThinking(true)
                .build();
        this.estimator = AiServices.builder(Estimator.class).chatModel(model).build();
    }

    @Override
    public LlmResult chat(String systemPrompt, List<ChatTurn> history, String userMessage, Object tools, int maxToolCalls) {
        long start = System.currentTimeMillis();
        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(history.size() + 40);
        for (ChatTurn t : history) {
            memory.add(t.fromUser() ? UserMessage.from(t.content()) : AiMessage.from(t.content()));
        }
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemory(memory)
                .tools(tools)
                .maxSequentialToolsInvocations(maxToolCalls + 2)
                .build();
        Result<String> result = assistant.chat(userMessage);
        int in = result.tokenUsage() == null || result.tokenUsage().inputTokenCount() == null ? 0 : result.tokenUsage().inputTokenCount();
        int out = result.tokenUsage() == null || result.tokenUsage().outputTokenCount() == null ? 0 : result.tokenUsage().outputTokenCount();
        int calls = result.toolExecutions() == null ? 0 : result.toolExecutions().size();
        log.info("llm model={} tokensIn={} tokensOut={} tools={} latencyMs={}", modelName, in, out, calls,
                System.currentTimeMillis() - start);
        return new LlmResult(result.content(), in, out, calls);
    }

    @Override
    public List<DishEstimate> estimate(List<EstimateRequest> requests, String originalMessage) {
        long start = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("Исходное сообщение пользователя: «").append(originalMessage).append("»\n\nПозиции:\n");
        for (int i = 0; i < requests.size(); i++) {
            EstimateRequest r = requests.get(i);
            sb.append(i + 1).append(". ").append(r.name());
            if (r.grams() != null) {
                sb.append(" — порция ").append(Math.round(r.grams())).append(" г");
            } else if (r.kcal() != null) {
                sb.append(" — съедено ").append(Math.round(r.kcal())).append(" ккал");
            }
            sb.append('\n');
        }
        Estimates result = estimator.estimate(sb.toString());
        List<DishEstimate> items = result == null || result.items() == null ? List.of() : result.items();
        log.info("llm estimate model={} items={} returned={} latencyMs={}", modelName, requests.size(), items.size(),
                System.currentTimeMillis() - start);
        return items;
    }
}
