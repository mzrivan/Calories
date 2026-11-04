package kz.calories.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Telegram telegram,
        List<Long> allowedTelegramIds,
        Llm llm,
        Agent agent,
        Validation validation,
        Search search,
        Drafts drafts,
        Energy energy) {

    public record Telegram(boolean enabled, String token) {}

    public record Llm(String apiKey, String model,
                      double temperature, int maxToolCalls, int retries, long retryInitialDelayMs) {}

    public record Agent(int historyMessages, int historyMinutes, int rateLimitPerMinute, int maxValidationRetries) {}

    public record Validation(double maxKcalPer100g, double maxMacroPer100g, double maxMacroSumPer100g,
                             double atwaterTolerance, double atwaterMinAbsKcal, double maxGrams,
                             double maxCookedWeightG, double minCookedRatio, double maxCookedRatio) {}

    public record Search(double trgmThreshold, double approxThreshold, double approxGap, int topK) {}

    public record Energy(double tefFactor, double stepLengthFactor, double walkKcalPerKgKm) {}

    public record Drafts(int ttlHours) {}

    public List<Long> allowedTelegramIds() {
        return allowedTelegramIds == null ? List.of() : allowedTelegramIds;
    }
}
