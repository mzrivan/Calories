package kz.calories.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
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

    private final ChatModel model;
    private final String modelName;

    public GeminiLlm(AppProperties props) {
        AppProperties.Llm cfg = props.llm();
        this.modelName = cfg.model();
        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(cfg.apiKey().isBlank() ? "missing-key" : cfg.apiKey())
                .modelName(cfg.model())
                .temperature(cfg.temperature())
                .build();
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
}
