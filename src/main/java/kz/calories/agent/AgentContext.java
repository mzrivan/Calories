package kz.calories.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Состояние одного хода агента: что изменилось, какие карточки показать, счётчики лимитов. */
public class AgentContext {

    /** Карточка с кнопками; confirm/cancel — префиксы callback-данных (+ ":" + draftId). */
    public record DraftCard(long draftId, String text, String confirmPrefix, String cancelPrefix) {
        public static DraftCard dish(long draftId, String text) {
            return new DraftCard(draftId, text, "save", "cancel");
        }

        public static DraftCard meals(long draftId, String text) {
            return new DraftCard(draftId, text, "mconfirm", "mcancel");
        }
    }

    private final List<DraftCard> cards = new ArrayList<>();
    private final List<kz.calories.validation.Nutrition> added = new ArrayList<>();
    private final Map<String, Integer> validationFailures = new HashMap<>();
    private java.time.LocalDate balanceDay;
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

    /** Записана порция в этом ходе — нужна для суммы «Всего добавлено», если записей несколько. */
    public void added(kz.calories.validation.Nutrition n) {
        added.add(n);
    }

    public List<kz.calories.validation.Nutrition> addedNutrition() {
        return added;
    }

    /** Показать итоги дня в конце ответа без побочных эффектов. */
    public void showTotals() {
        this.dayChanged = true;
    }

    /** Дописать к ответу блок «Расход и баланс» за указанный день. */
    public void showBalance(java.time.LocalDate day) {
        this.balanceDay = day;
    }

    public java.time.LocalDate balanceDay() {
        return balanceDay;
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
