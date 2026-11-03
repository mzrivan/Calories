package kz.calories.user;

import java.time.ZoneId;

public record AppUser(long id, long telegramId, String name, ZoneId zone, Goals goals) {

    public record Goals(Double kcal, Double protein, Double fat, Double carbs, Double fiber) {
        public boolean any() {
            return kcal != null || protein != null || fat != null || carbs != null || fiber != null;
        }
    }
}
