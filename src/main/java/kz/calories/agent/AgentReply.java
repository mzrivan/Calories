package kz.calories.agent;

import java.util.List;

/** Ответ агента: текст (уже с блоком «Итого за сегодня», если нужно) и карточки блюд для подтверждения. */
public record AgentReply(String text, List<AgentContext.DraftCard> cards) {

    public static AgentReply text(String text) {
        return new AgentReply(text, List.of());
    }
}
