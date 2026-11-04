package kz.calories.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentParserTest {

    @Test
    void weightVariants() {
        assertThat(IntentParser.parse("вес 82,5").weightKg()).isEqualTo(82.5);
        assertThat(IntentParser.parse("Вешу 82 кг").weightKg()).isEqualTo(82.0);
        assertThat(IntentParser.parse("82.5 кг").weightKg()).isEqualTo(82.5);
    }

    @Test
    void stepsVariants() {
        assertThat(IntentParser.parse("шагов 9500").steps()).isEqualTo(9500);
        assertThat(IntentParser.parse("9500 шагов").steps()).isEqualTo(9500);
        assertThat(IntentParser.parse("прошёл 9.5к шагов").steps()).isEqualTo(9500);
        assertThat(IntentParser.parse("шаги: 12000").steps()).isEqualTo(12000);
    }

    @Test
    void severalValuesInOneMessage() {
        IntentParser.Intent in = IntentParser.parse("вес 82,5, шагов 9500");
        assertThat(in.weightKg()).isEqualTo(82.5);
        assertThat(in.steps()).isEqualTo(9500);
        assertThat(in.remainder()).isEmpty();
    }

    @Test
    void profileInOneMessage() {
        IntentParser.Intent in = IntentParser.parse("рост 178, возраст 35, пол м");
        assertThat(in.heightCm()).isEqualTo(178.0);
        assertThat(in.age()).isEqualTo(35);
        assertThat(in.sex()).isEqualTo("M");
        assertThat(IntentParser.parse("пол ж").sex()).isEqualTo("F");
    }

    @Test
    void yesterdayShiftsDay() {
        IntentParser.Intent in = IntentParser.parse("вчера шагов 8000");
        assertThat(in.yesterday()).isTrue();
        assertThat(in.steps()).isEqualTo(8000);
    }

    @Test
    void queries() {
        assertThat(IntentParser.parse("баланс").query()).isEqualTo(IntentParser.Query.BALANCE);
        assertThat(IntentParser.parse("Итог дня").query()).isEqualTo(IntentParser.Query.SUMMARY);
        assertThat(IntentParser.parse("сколько осталось").query()).isEqualTo(IntentParser.Query.SUMMARY);
        assertThat(IntentParser.parse("расход за сегодня").query()).isEqualTo(IntentParser.Query.BALANCE);
    }

    @Test
    void goalOnlyWhenWholeMessage() {
        assertThat(IntentParser.parse("цель 2000 ккал").goalKcal()).isEqualTo(2000.0);
        assertThat(IntentParser.parse("цель 2000 ккал, белка 120 г").any()).isFalse(); // сложный случай — LLM
    }

    @Test
    void foodMessagesAreNotIntents() {
        assertThat(IntentParser.parse("Хлеб 13, картошка с курицей 300, мандарин 60").any()).isFalse();
        assertThat(IntentParser.parse("ростбиф 100").any()).isFalse();
        assertThat(IntentParser.parse("Мандарин 100, спагетти с песто 111").any()).isFalse();
    }

    @Test
    void remainderKeepsFoodPart() {
        IntentParser.Intent in = IntentParser.parse("вес 82, хлеб 30, молоко 1,5% 100");
        assertThat(in.weightKg()).isEqualTo(82.0);
        assertThat(in.remainder()).isEqualTo("хлеб 30, молоко 1,5% 100");
    }
}
