package kz.calories.energy;

import kz.calories.config.AppProperties;
import kz.calories.validation.Nutrition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Расход калорий по стандартным формулам. Чистые вычисления, без БД. */
@Component
public class EnergyCalculator {

    private final AppProperties.Energy cfg;

    @Autowired
    public EnergyCalculator(AppProperties props) {
        this(props.energy());
    }

    public EnergyCalculator(AppProperties.Energy cfg) {
        this.cfg = cfg;
    }

    /** Основной обмен по Миффлину–Сан-Жеору, ккал/сутки. sex: "M" или "F". */
    public double bmr(double weightKg, double heightCm, int age, String sex) {
        return 10 * weightKg + 6.25 * heightCm - 5 * age + ("M".equals(sex) ? 5 : -161);
    }

    /**
     * Термический эффект пищи (ТЭП) по съеденным за день макронутриентам: доля от калорийности каждого
     * (белок 4, углеводы 4, жиры 9 ккал/г). Коэффициенты активности не используются, двойного учёта нет.
     */
    public double tef(Nutrition eaten) {
        return eaten.protein() * 4 * cfg.tefProtein()
                + eaten.carbs() * 4 * cfg.tefCarbs()
                + eaten.fat() * 9 * cfg.tefFat();
    }

    /** Чистый расход на ходьбу: длина шага = рост × коэффициент; ккал = k × кг × км. */
    public double stepsKcal(int steps, double weightKg, double heightCm) {
        double km = steps * (heightCm * cfg.stepLengthFactor() / 100.0) / 1000.0;
        return cfg.walkKcalPerKgKm() * weightKg * km;
    }
}
