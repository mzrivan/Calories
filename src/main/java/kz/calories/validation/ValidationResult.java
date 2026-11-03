package kz.calories.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * hard — ошибки, которые нельзя принять ни при каких условиях (отрицательные числа, нет веса);
 * soft — несоответствия (Атуотер, диапазоны), которые после исчерпания попыток принимаются как «низкая уверенность».
 */
public record ValidationResult(List<String> hard, List<String> soft) {

    public static ValidationResult empty() {
        return new ValidationResult(new ArrayList<>(), new ArrayList<>());
    }

    public boolean ok() {
        return hard.isEmpty() && soft.isEmpty();
    }

    public boolean onlySoft() {
        return hard.isEmpty() && !soft.isEmpty();
    }

    public List<String> all() {
        List<String> all = new ArrayList<>(hard);
        all.addAll(soft);
        return all;
    }

    public String message() {
        return String.join("; ", all());
    }
}
