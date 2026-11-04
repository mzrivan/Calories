package kz.calories.energy;

import kz.calories.config.AppProperties;
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

    /** Основной обмен с надбавкой на термический эффект пищи и бытовую активность. */
    public double baseExpenditure(double bmr) {
        return bmr * cfg.tefFactor();
    }

    /** Чистый расход на ходьбу: длина шага = рост × коэффициент; ккал = k × кг × км. */
    public double stepsKcal(int steps, double weightKg, double heightCm) {
        double km = steps * (heightCm * cfg.stepLengthFactor() / 100.0) / 1000.0;
        return cfg.walkKcalPerKgKm() * weightKg * km;
    }
}
