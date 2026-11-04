package kz.calories.user;

import java.time.ZoneId;

public record AppUser(long id, long telegramId, String name, ZoneId zone, Goals goals, Profile profile) {

    public record Goals(Double kcal, Double protein, Double fat, Double carbs, Double fiber) {
        public boolean any() {
            return kcal != null || protein != null || fat != null || carbs != null || fiber != null;
        }
    }

    /** Данные для расчёта расхода калорий; sex: "M" или "F". */
    public record Profile(Double heightCm, Integer birthYear, String sex) {
        public static final Profile EMPTY = new Profile(null, null, null);

        public boolean complete() {
            return heightCm != null && birthYear != null && sex != null;
        }
    }
}
