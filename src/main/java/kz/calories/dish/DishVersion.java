package kz.calories.dish;

import java.time.OffsetDateTime;
import kz.calories.validation.Nutrition;

/** Иммутабельная версия блюда/продукта; значения на 100 г. */
public record DishVersion(long id, String name, String normalizedName, Kind kind, Nutrition per100,
                          Double cookedWeightG, String sourceText, String method, String confidence,
                          String assumptions, long createdByUserId, OffsetDateTime createdAt, boolean active) {

    public enum Kind { RECIPE, PRODUCT }
}
