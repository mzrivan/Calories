package kz.calories.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Состояние одного хода агента: что изменилось, какие карточки показать, счётчики лимитов. */
public class AgentContext {

    public record DraftCard(long draftId, String text) {}

    private final List<DraftCard> cards = new ArrayList<>();
    private final Map<String, Integer> validationFailures = new HashMap<>();
    private boolean dayChanged;
    private boolean wrote;
    private int toolCalls;

    public List<DraftCard> cards() {
        return cards;
    }

    /** Изменились записи дня — ответ завершится блоком «Итого за сегодня». */
    public boolean dayChanged() {
        return dayChanged;
    }

    public void markDayChanged() {
        this.dayChanged = true;
        this.wrote = true;
    }

    /** Показать итоги дня в конце ответа без побочных эффектов. */
    public void showTotals() {
        this.dayChanged = true;
    }

    public void markWrote() {
        this.wrote = true;
    }

    /** Был ли побочный эффект (запись/черновик): после него повтор запроса к LLM небезопасен. */
    public boolean wrote() {
        return wrote;
    }

    public int nextToolCall() {
        return ++toolCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int failure(String tool) {
        return validationFailures.merge(tool, 1, Integer::sum);
    }
}
