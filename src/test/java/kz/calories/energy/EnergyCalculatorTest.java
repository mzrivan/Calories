package kz.calories.energy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import kz.calories.config.AppProperties;
import kz.calories.validation.Nutrition;
import org.junit.jupiter.api.Test;

class EnergyCalculatorTest {

    private final EnergyCalculator calc = new EnergyCalculator(new AppProperties.Energy(0.25, 0.075, 0.015, 0.415, 0.5));

    @Test
    void mifflinStJeorMale() {
        // 10*80 + 6.25*180 - 5*30 + 5 = 800 + 1125 - 150 + 5 = 1780
        assertThat(calc.bmr(80, 180, 30, "M")).isCloseTo(1780, within(0.001));
    }

    @Test
    void mifflinStJeorFemale() {
        // 10*60 + 6.25*165 - 5*40 - 161 = 600 + 1031.25 - 200 - 161 = 1270.25
        assertThat(calc.bmr(60, 165, 40, "F")).isCloseTo(1270.25, within(0.001));
    }

    @Test
    void tefFromMacros() {
        // 100*4*0.25 + 250*4*0.075 + 60*9*0.015 = 100 + 75 + 8.1
        assertThat(calc.tef(new Nutrition(2000, 100, 60, 250, null))).isCloseTo(183.1, within(0.001));
        assertThat(calc.tef(Nutrition.ZERO)).isZero();
    }

    @Test
    void stepsKcal() {
        // шаг = 180*0.415 = 74.7 см; 10000 шагов = 7.47 км; 0.5*80*7.47 = 298.8
        assertThat(calc.stepsKcal(10_000, 80, 180)).isCloseTo(298.8, within(0.01));
        assertThat(calc.stepsKcal(0, 80, 180)).isZero();
    }
}
