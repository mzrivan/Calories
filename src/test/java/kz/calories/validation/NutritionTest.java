package kz.calories.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class NutritionTest {

    @Test
    void per100DividesTotalByCookedWeight() {
        Nutrition total = new Nutrition(1650, 90, 60, 180, 30.0);
        Nutrition per100 = total.per100(3000);
        assertThat(per100.kcal()).isCloseTo(55, within(0.001));
        assertThat(per100.fiber()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void forGramsScalesPer100() {
        Nutrition portion = new Nutrition(55, 3, 2, 6, 1.0).forGrams(300);
        assertThat(portion.kcal()).isCloseTo(165, within(0.001));
        assertThat(portion.fiber()).isCloseTo(3.0, within(0.001));
    }

    @Test
    void missingFiberStaysMissing() {
        assertThat(new Nutrition(55, 3, 2, 6, null).forGrams(300).fiber()).isNull();
        Nutrition sum = new Nutrition(100, 1, 1, 1, null).plus(new Nutrition(50, 1, 1, 1, 2.0));
        assertThat(sum.fiber()).isEqualTo(2.0);
        assertThat(new Nutrition(1, 1, 1, 1, null).plus(Nutrition.ZERO).fiber()).isNull();
    }
}
