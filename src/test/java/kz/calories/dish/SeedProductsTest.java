package kz.calories.dish;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kz.calories.config.AppProperties;
import kz.calories.validation.Nutrition;
import kz.calories.validation.NutritionValidator;
import kz.calories.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Справочник базовых продуктов (миграция 004) проходит те же проверки, что и данные от пользователя. */
class SeedProductsTest {

    private final NutritionValidator validator = new NutritionValidator(new AppProperties(
            null, List.of(), null, null,
            new AppProperties.Validation(900, 100, 105, 0.15, 8, 5000, 50000, 0.2, 3.0), null, null, null));

    private List<String> lines(String path) throws IOException {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).filter(l -> !l.isBlank()).toList();
        }
    }

    @Test
    void productsAreValidAndNamesNormalized() throws IOException {
        Set<String> names = new HashSet<>();
        List<String> rows = lines("db/seed/products.csv");
        assertThat(rows.size()).isGreaterThan(80);
        for (String row : rows) {
            String[] c = row.split(",", -1);
            assertThat(c).as(row).hasSize(10);
            assertThat(c[1]).as("normalized_name " + row).isEqualTo(Names.normalize(c[0]));
            assertThat(names.add(c[1])).as("дубликат " + c[1]).isTrue();
            Double fiber = c[7].equals("NULL") ? null : Double.valueOf(c[7]);
            ValidationResult vr = validator.validatePer100(new Nutrition(Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                    Double.parseDouble(c[5]), Double.parseDouble(c[6]), fiber));
            assertThat(vr.ok()).as(row + " → " + vr.message()).isTrue();
        }
    }

    @Test
    void aliasesPointToExistingProducts() throws IOException {
        Set<String> names = new HashSet<>();
        lines("db/seed/products.csv").forEach(r -> names.add(r.split(",", -1)[1]));
        for (String row : lines("db/seed/aliases.csv")) {
            String[] c = row.split(",", -1);
            assertThat(c[0]).as("alias normalized " + row).isEqualTo(Names.normalize(c[0]));
            assertThat(names).as("цель алиаса " + row).contains(c[1]);
            assertThat(names).as("алиас не должен совпадать с продуктом " + row).doesNotContain(c[0]);
        }
    }
}
