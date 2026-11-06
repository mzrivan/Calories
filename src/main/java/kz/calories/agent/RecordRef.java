package kz.calories.agent;

/** Записи, к которым относится конкретное сообщение бота, без итогов и справочных данных. */
public record RecordRef(Kind kind, long id) {
    public enum Kind { MEAL, ACTIVITY, DISH, DISH_DRAFT, MEAL_DRAFT }
}
