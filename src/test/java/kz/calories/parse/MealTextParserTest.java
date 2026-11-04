package kz.calories.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Примеры — реальные сообщения пользователей. «LLM» = парсер не уверен и отдаёт сообщение модели. */
class MealTextParserTest {

    private static String show(String text) {
        MealTextParser.ParsedMessage m = MealTextParser.parse(text);
        if (!m.ok()) {
            return "LLM";
        }
        return m.items().stream().map(i -> i.name().toLowerCase(Locale.ROOT) + "="
                        + (i.grams() != null ? trim(i.grams()) : trim(i.kcal()) + "ккал"))
                .collect(Collectors.joining("; "));
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Хлеб 13, картошка с курицей 300, мандарин 60 | хлеб=13; картошка с курицей=300; мандарин=60
            Спагетти с тушенкой 132 | спагетти с тушенкой=132
            Молоко 60 | молоко=60
            Хлебец на 30ккал | хлебец=30ккал
            Виноград 30 кишмиш | виноград кишмиш=30
            Хлеб 26 масло 8 сыр масдам 25 | хлеб=26; масло=8; сыр масдам=25
            Суп 257, сосиска 103, картошка 67 | суп=257; сосиска=103; картошка=67
            Брокколи 50, кетчуп домашний 30 | брокколи=50; кетчуп домашний=30
            70 йогурт теос, 7 карамель хайнц | йогурт теос=70; карамель хайнц=7
            Молоко 70, шашлык из баранины 30, хинкали 100 | молоко=70; шашлык из баранины=30; хинкали=100
            Хлеб 17, паштет из куриной печени 17, песто 17 | хлеб=17; паштет из куриной печени=17; песто=17
            Оладьи ещё 25, 16 йогурт | оладьи=25; йогурт=16
            Съел 300 г борща | борща=300
            Выпил молоко 150 | молоко=150
            167 супа чечевичного, 9 хлеба | супа чечевичного=167; хлеба=9
            Шоколад молочный 5 | шоколад молочный=5
            58 йогурт теос, кукуруза консервированная 40 | йогурт теос=58; кукуруза консервированная=40
            Хлеб 17, сыр йогуртовый хохланд 17, ветчина 43, молоко 50 | хлеб=17; сыр йогуртовый хохланд=17; ветчина=43; молоко=50
            Оладьи 30, сосиска 95, брокколи 100, фарш с луком 37 | оладьи=30; сосиска=95; брокколи=100; фарш с луком=37
            Яблоко 180, помело 50 | яблоко=180; помело=50
            Мандарин 100, спагетти с песто 111, форель 40 | мандарин=100; спагетти с песто=111; форель=40
            Олодьи 62, йогурт 42 | олодьи=62; йогурт=42
            """)
    void parsesLists(String input, String expected) {
        assertThat(show(input.trim())).isEqualTo(expected.trim());
    }

    @Test
    void fatPercentIsPartOfTheName() {
        assertThat(show("60 молоко, 135 творог 0.6%, 95 йогурт питьевой 1,5 жирности, шоколад тёмный Горький 5, гранат 15"))
                .isEqualTo("молоко=60; творог 0.6%=135; йогурт питьевой 1.5%=95; шоколад тёмный горький=5; гранат=15");
    }

    @Test
    void kilogramsAndUnits() {
        assertThat(show("Курица 0,3 кг, хлеб 30 г")).isEqualTo("курица=300; хлеб=30");
    }

    @Test
    void ambiguousOrIncompleteGoToLlm() {
        // две позиции слиплись, жирность и вес неразличимы
        assertThat(show("83 творог 0.1 йогурт 40 1.5")).isEqualTo("LLM");
        // «готовые» — не название, а уточнение к предыдущей позиции
        assertThat(show("Спагетти сухие 172 готовые 440, плюс песто 100")).isEqualTo("LLM");
        // КБЖУ в скобках — нужен разбор моделью
        assertThat(show("74 спагетти с песто, 74 фарш говяжий жареный с луком (КБЖУ на 100 г готового фарша: 190 ккал / Б 24,9 г) кетчуп домашний 35"))
                .isEqualTo("LLM");
        // позиция без веса
        assertThat(show("Вафля сырная 40, кетчуп домашний помидоры перцы без сахара, сосиска домашняя куриная 55")).isEqualTo("LLM");
        // это не список
        assertThat(show("съел вчера много всего")).isEqualTo("LLM");
        assertThat(show("")).isEqualTo("LLM");
    }
}
