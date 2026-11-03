package kz.calories.meal;

import kz.calories.validation.Nutrition;

/** Итоги дня. nutrition.fiber() == null, если клетчатки в записях нет вовсе. */
public record DayTotals(Nutrition nutrition, int entries) {
}
