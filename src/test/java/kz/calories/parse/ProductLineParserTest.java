package kz.calories.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductLineParserTest {

    @Test
    void withoutFiber() {
        var p = ProductLineParser.parse("Творог президент 56/12/0.1/1.8").orElseThrow();
        assertThat(p.name()).isEqualTo("Творог президент");
        assertThat(p.per100().kcal()).isEqualTo(56);
        assertThat(p.per100().protein()).isEqualTo(12);
        assertThat(p.per100().fat()).isEqualTo(0.1);
        assertThat(p.per100().carbs()).isEqualTo(1.8);
        assertThat(p.per100().fiber()).isNull();
    }

    @Test
    void withFiberCommaAndSpaces() {
        var p = ProductLineParser.parse("Хлеб Бородинский 5% : 208 / 6,9 / 1,3 / 40,9 / 5,8").orElseThrow();
        assertThat(p.name()).isEqualTo("Хлеб Бородинский 5%");
        assertThat(p.per100().fiber()).isEqualTo(5.8);
    }

    @Test
    void nameWithNumber() {
        assertThat(ProductLineParser.parse("Йогурт 2 56/12/0.1/1.8").orElseThrow().name()).isEqualTo("Йогурт 2");
    }

    @Test
    void notAProduct() {
        assertThat(ProductLineParser.parse("хлеб 30, молоко 100")).isEmpty();
        assertThat(ProductLineParser.parse("56/12/0.1/1.8")).isEmpty();
        assertThat(ProductLineParser.parse("творог 56/12/0.1")).isEmpty();
        assertThat(ProductLineParser.parse("и 56/12/0.1/1.8")).isEmpty();
    }
}
