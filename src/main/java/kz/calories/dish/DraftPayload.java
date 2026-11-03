package kz.calories.dish;

import kz.calories.validation.Nutrition;

/** Содержимое черновика новой версии блюда (хранится как JSON в dish_draft.payload). */
public record DraftPayload(String name, DishVersion.Kind kind, Nutrition per100, Double cookedWeightG,
                           Double totalKcal, String sourceText, String method, String confidence, String assumptions) {
}
