package kz.calories.agent;

import java.util.List;

/**
 * Ответ бота: текст (уже с блоком «Итого за сегодня», если нужно), карточки для подтверждения
 * и ссылки на записи для последующей правки пересылкой.
 */
public record AgentReply(String text, List<AgentContext.DraftCard> cards, String undoBatchId, List<RecordRef> records) {

    public AgentReply(String text, List<AgentContext.DraftCard> cards, String undoBatchId) {
        this(text, cards, undoBatchId, List.of());
    }

    public AgentReply(String text, List<AgentContext.DraftCard> cards) {
        this(text, cards, null);
    }

    public static AgentReply text(String text) {
        return new AgentReply(text, List.of(), null);
    }

    public AgentReply withUndo(String batchId) {
        return new AgentReply(text, cards, batchId, records);
    }

    public AgentReply withRecords(List<RecordRef> refs) {
        return new AgentReply(text, cards, undoBatchId, List.copyOf(refs));
    }

    /** Склейка двух ответов (например, данные активности + разбор остатка сообщения). */
    public AgentReply and(AgentReply other) {
        List<AgentContext.DraftCard> all = new java.util.ArrayList<>(cards);
        all.addAll(other.cards);
        List<RecordRef> refs = new java.util.ArrayList<>(records);
        refs.addAll(other.records);
        return new AgentReply(text + "\n\n" + other.text, all, other.undoBatchId != null ? other.undoBatchId : undoBatchId, refs);
    }
}
