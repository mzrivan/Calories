package kz.calories.validation;

import java.util.Locale;
import kz.calories.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class NutritionValidator {

    private final AppProperties.Validation cfg;

    public NutritionValidator(AppProperties props) {
        this.cfg = props.validation();
    }

    /** Проверка значений на 100 г: неотрицательность, диапазоны, Атуотер, клетчатка ≤ углеводов. */
    public ValidationResult validatePer100(Nutrition n) {
        ValidationResult r = ValidationResult.empty();
        if (n.kcal() < 0 || n.protein() < 0 || n.fat() < 0 || n.carbs() < 0 || (n.fiber() != null && n.fiber() < 0)) {
            r.hard().add("значения не могут быть отрицательными");
            return r;
        }
        if (n.kcal() > cfg.maxKcalPer100g()) {
            r.soft().add(fmt("%.0f ккал на 100 г — больше максимума %.0f (проверьте вес готового блюда и суммы)",
                    n.kcal(), cfg.maxKcalPer100g()));
        }
        if (n.protein() > cfg.maxMacroPer100g() || n.fat() > cfg.maxMacroPer100g() || n.carbs() > cfg.maxMacroPer100g()) {
            r.soft().add("белки, жиры или углеводы на 100 г больше 100 г");
        }
        if (n.protein() + n.fat() + n.carbs() > cfg.maxMacroSumPer100g()) {
            r.soft().add(fmt("Б+Ж+У на 100 г = %.0f г, это больше %.0f г", n.protein() + n.fat() + n.carbs(),
                    cfg.maxMacroSumPer100g()));
        }
        double expected = 4 * n.protein() + 9 * n.fat() + 4 * n.carbs();
        double allowed = Math.max(cfg.atwaterTolerance() * expected, cfg.atwaterMinAbsKcal());
        if (Math.abs(n.kcal() - expected) > allowed) {
            r.soft().add(fmt("калории не сходятся с БЖУ: заявлено %.0f ккал, по формуле 4·Б+9·Ж+4·У = %.0f ккал (допуск ±%.0f%%)",
                    n.kcal(), expected, cfg.atwaterTolerance() * 100));
        }
        if (n.fiber() != null && n.fiber() > n.carbs() + 0.05) {
            r.soft().add("клетчатка больше углеводов (углеводы указываются ВМЕСТЕ с клетчаткой)");
        }
        return r;
    }

    /** Проверка веса рецепта: готовый вес обязателен и правдоподобен относительно сырого. */
    public ValidationResult validateRecipeWeights(Double cookedWeightG, Double rawWeightG) {
        ValidationResult r = ValidationResult.empty();
        if (cookedWeightG == null || cookedWeightG <= 0) {
            r.hard().add("не указан вес готового блюда: спросите его у пользователя, не оценивайте молча");
            return r;
        }
        if (cookedWeightG > cfg.maxCookedWeightG()) {
            r.hard().add("вес готового блюда нереалистичен");
            return r;
        }
        if (rawWeightG != null && rawWeightG > 0) {
            double ratio = cookedWeightG / rawWeightG;
            if (ratio < cfg.minCookedRatio() || ratio > cfg.maxCookedRatio()) {
                r.soft().add(fmt("вес готового (%.0f г) неправдоподобен для сырых ингредиентов %.0f г (отношение %.2f, допустимо %.2f–%.2f)",
                        cookedWeightG, rawWeightG, ratio, cfg.minCookedRatio(), cfg.maxCookedRatio()));
            }
        }
        return r;
    }

    public ValidationResult validateGrams(double grams) {
        ValidationResult r = ValidationResult.empty();
        if (grams <= 0 || Double.isNaN(grams)) {
            r.hard().add("граммы должны быть положительным числом");
        } else if (grams > cfg.maxGrams()) {
            r.hard().add(fmt("%.0f г — больше допустимого максимума порции %.0f г", grams, cfg.maxGrams()));
        }
        return r;
    }

    /** Атуотер и неотрицательность для абсолютных значений (например, правка записи). */
    public ValidationResult validateTotal(Nutrition n) {
        ValidationResult r = ValidationResult.empty();
        if (n.kcal() < 0 || n.protein() < 0 || n.fat() < 0 || n.carbs() < 0 || (n.fiber() != null && n.fiber() < 0)) {
            r.hard().add("значения не могут быть отрицательными");
            return r;
        }
        double expected = 4 * n.protein() + 9 * n.fat() + 4 * n.carbs();
        double allowed = Math.max(cfg.atwaterTolerance() * expected, cfg.atwaterMinAbsKcal());
        if (Math.abs(n.kcal() - expected) > allowed) {
            r.soft().add(fmt("калории не сходятся с БЖУ: %.0f ккал против %.0f по формуле", n.kcal(), expected));
        }
        if (n.fiber() != null && n.fiber() > n.carbs() + 0.05) {
            r.soft().add("клетчатка больше углеводов");
        }
        return r;
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
