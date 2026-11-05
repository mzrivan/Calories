package kz.calories.meal;

import java.time.OffsetDateTime;
import kz.calories.validation.Nutrition;

/** Запись дневника: снимок значений на момент записи (для порции целиком, не на 100 г). */
public record MealEntry(long id, long userId, OffsetDateTime eatenAt, String name, Long dishVersionId, double grams,
                        Nutrition nutrition, String confidence, String assumptions, MealType mealType) {
}
