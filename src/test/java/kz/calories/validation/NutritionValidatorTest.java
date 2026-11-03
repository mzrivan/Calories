package kz.calories.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kz.calories.config.AppProperties;
import org.junit.jupiter.api.Test;

class NutritionValidatorTest {

    private final NutritionValidator validator = new NutritionValidator(new AppProperties(
            null, List.of(), null, null,
            new AppProperties.Validation(900, 100, 105, 0.15, 8, 5000, 50000, 0.2, 3.0), null, null));

    @Test
    void consistentValuesPass() {
        // 4*16 + 9*5 + 4*3 = 121
        assertThat(validator.validatePer100(new Nutrition(121, 16, 5, 3, null)).ok()).isTrue();
    }

    @Test
    void atwaterMismatchIsSoftError() {
        ValidationResult r = validator.validatePer100(new Nutrition(300, 16, 5, 3, null));
        assertThat(r.onlySoft()).isTrue();
        assertThat(r.message()).contains("не сходятся");
    }

    @Test
    void negativeIsHardError() {
        ValidationResult r = validator.validatePer100(new Nutrition(100, -1, 5, 3, null));
        assertThat(r.hard()).isNotEmpty();
    }

    @Test
    void fiberAboveCarbsFails() {
        assertThat(validator.validatePer100(new Nutrition(48, 0, 0, 12, 20.0)).soft()).anyMatch(s -> s.contains("клетчатка"));
    }

    @Test
    void missingFiberIsNotAnError() {
        assertThat(validator.validatePer100(new Nutrition(121, 16, 5, 3, null)).ok()).isTrue();
    }

    @Test
    void recipeWithoutCookedWeightIsHardError() {
        assertThat(validator.validateRecipeWeights(null, 1500.0).hard()).isNotEmpty();
        assertThat(validator.validateRecipeWeights(0.0, 1500.0).hard()).isNotEmpty();
    }

    @Test
    void cookedWeightPlausibilityRelativeToRaw() {
        assertThat(validator.validateRecipeWeights(3000.0, 1500.0).ok()).isTrue();
        assertThat(validator.validateRecipeWeights(100.0, 1500.0).onlySoft()).isTrue();
        assertThat(validator.validateRecipeWeights(9000.0, 1500.0).onlySoft()).isTrue();
    }

    @Test
    void gramsLimits() {
        assertThat(validator.validateGrams(300).ok()).isTrue();
        assertThat(validator.validateGrams(0).hard()).isNotEmpty();
        assertThat(validator.validateGrams(99999).hard()).isNotEmpty();
    }

    @Test
    void implausiblePer100IsFlagged() {
        assertThat(validator.validatePer100(new Nutrition(2000, 100, 100, 100, null)).ok()).isFalse();
    }
}
