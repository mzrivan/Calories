package kz.calories.agent;

import java.util.List;

/**
 * Ответ бота: текст (уже с блоком «Итого за сегодня», если нужно), карточки для подтверждения
 * и необязательный идентификатор пачки записей для кнопки «Отменить».
 */
public record AgentReply(String text, List<AgentContext.DraftCard> cards, String undoBatchId) {

    public AgentReply(String text, List<AgentContext.DraftCard> cards) {
        this(text, cards, null);
    }

    public static AgentReply text(String text) {
        return new AgentReply(text, List.of(), null);
    }

    public AgentReply withUndo(String batchId) {
        return new AgentReply(text, cards, batchId);
    }

    /** Склейка двух ответов (например, данные активности + разбор остатка сообщения). */
    public AgentReply and(AgentReply other) {
        List<AgentContext.DraftCard> all = new java.util.ArrayList<>(cards);
        all.addAll(other.cards);
        return new AgentReply(text + "\n\n" + other.text, all, other.undoBatchId != null ? other.undoBatchId : undoBatchId);
    }
}
