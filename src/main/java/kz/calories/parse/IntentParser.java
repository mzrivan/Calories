package kz.calories.parse;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Распознавание по ключевым словам без LLM: вес, шаги, профиль (рост/возраст/пол), цель по калориям,
 * запросы итогов/баланса. В одном сообщении может быть несколько значений: «вес 82,5, шагов 9500».
 * То, что не опознано, возвращается в remainder (его разбирают дальше как еду или отдают LLM).
 */
public final class IntentParser {

    public enum Query { NONE, SUMMARY, BALANCE }

    public record Intent(Double weightKg, Integer steps, Double heightCm, Integer age, String sex, Double goalKcal,
                         boolean yesterday, Query query, String remainder) {

        /** Есть данные для записи (вес/шаги/профиль/цель). */
        public boolean hasData() {
            return weightKg != null || steps != null || heightCm != null || age != null || sex != null || goalKcal != null;
        }

        public boolean any() {
            return hasData() || query != Query.NONE;
        }

        public boolean hasProfile() {
            return heightCm != null || age != null || sex != null;
        }
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";

    private static final Pattern WEIGHT = Pattern.compile("(?:\\bвес|\\bвешу|\\bвзвесил\\p{L}*)\\s*[:=-]?\\s*" + NUMBER + "(?:\\s*кг)?", F);
    private static final Pattern WEIGHT_ONLY = Pattern.compile("^\\s*" + NUMBER + "\\s*кг\\.?\\s*$", F);
    private static final Pattern STEPS_AFTER = Pattern.compile("\\bшаг\\p{L}*\\s*[:=-]?\\s*" + NUMBER + "\\s*(тыс\\p{L}*|к\\b)?", F);
    private static final Pattern STEPS_BEFORE = Pattern.compile(NUMBER + "\\s*(тыс\\p{L}*|к)?\\s*шаг\\p{L}*", F);
    private static final Pattern HEIGHT = Pattern.compile("\\bрост\\s*[:=-]?\\s*" + NUMBER + "(?:\\s*см)?", F);
    private static final Pattern AGE = Pattern.compile("\\bвозраст\\s*[:=-]?\\s*(\\d{1,3})(?:\\s*(?:лет|года|год))?", F);
    private static final Pattern AGE_YEARS = Pattern.compile("\\b(\\d{1,3})\\s*(?:лет|года)\\b", F);
    private static final Pattern SEX = Pattern.compile("\\bпол\\s*[:=-]?\\s*(муж\\p{L}*|жен\\p{L}*|м|ж)\\b", F);
    private static final Pattern SEX_WORD = Pattern.compile("\\b(мужчина|женщина)\\b", F);
    private static final Pattern GOAL = Pattern.compile("^\\s*цель\\s*[:=-]?\\s*(\\d{3,5})\\s*(?:ккал)?\\s*$", F);
    private static final Pattern YESTERDAY = Pattern.compile("\\bвчера\\b", F);

    private static final Set<String> FILLERS = Set.of("сегодня", "за", "день", "дня", "прошел", "прошла", "прошёл",
            "мой", "моя", "мое", "моё", "теперь", "сейчас", "уже", "меня", "утром", "вечером", "сделал", "сделала");
    private static final Set<String> SUMMARY_WORDS = Set.of("итог", "итоги", "итого", "сводка", "сводку", "осталось",
            "сколько", "съел", "съела", "ел", "ела", "покажи", "показать", "дневник");
    private static final Set<String> BALANCE_WORDS = Set.of("баланс", "расход", "расходы", "дефицит", "профицит",
            "потратил", "потратила", "потрачено", "траты");

    private IntentParser() {}

    public static Intent parse(String text) {
        String t = text == null ? "" : text.trim();
        Matcher goal = GOAL.matcher(t);
        if (goal.matches()) {
            return new Intent(null, null, null, null, null, Double.parseDouble(goal.group(1)), false, Query.NONE, "");
        }
        Matcher wo = WEIGHT_ONLY.matcher(t);
        if (wo.matches() && num(wo.group(1)) >= 30) {
            return new Intent(num(wo.group(1)), null, null, null, null, null, false, Query.NONE, "");
        }
        Double weight = null;
        Integer steps = null;
        Double height = null;
        Integer age = null;
        String sex = null;
        String rest = t;

        Matcher m = WEIGHT.matcher(rest);
        if (m.find()) {
            weight = num(m.group(1));
            rest = cut(rest, m);
        }
        m = STEPS_AFTER.matcher(rest);
        if (m.find()) {
            steps = steps(m.group(1), m.group(2));
            rest = cut(rest, m);
        } else if ((m = STEPS_BEFORE.matcher(rest)).find()) {
            steps = steps(m.group(1), m.group(2));
            rest = cut(rest, m);
        }
        m = HEIGHT.matcher(rest);
        if (m.find()) {
            height = num(m.group(1));
            rest = cut(rest, m);
        }
        m = AGE.matcher(rest);
        if (m.find() || (m = AGE_YEARS.matcher(rest)).find()) {
            age = Integer.parseInt(m.group(1));
            rest = cut(rest, m);
        }
        m = SEX.matcher(rest);
        if (m.find()) {
            sex = m.group(1).toLowerCase(Locale.ROOT).startsWith("ж") ? "F" : "M";
            rest = cut(rest, m);
        } else if ((m = SEX_WORD.matcher(rest)).find()) {
            sex = m.group(1).toLowerCase(Locale.ROOT).startsWith("ж") ? "F" : "M";
            rest = cut(rest, m);
        }
        boolean yesterday = false;
        m = YESTERDAY.matcher(rest);
        if (m.find() && (weight != null || steps != null)) {
            yesterday = true;
            rest = cut(rest, m);
        }

        boolean data = weight != null || steps != null || height != null || age != null || sex != null;
        Query query = Query.NONE;
        String remainder = tidy(rest);
        String plain = remainder.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (!plain.isEmpty()) {
            Query q = classifyQuery(plain);
            if (q != Query.NONE) {
                query = q;
                remainder = "";
            }
        }
        if (data) {
            remainder = stripFillers(remainder);
        }
        return new Intent(weight, steps, height, age, sex, null, yesterday, query, remainder);
    }

    /** Запрос — только если все слова сообщения из словаря запросов/связок. */
    private static Query classifyQuery(String remainder) {
        boolean summary = false;
        boolean balance = false;
        for (String w : remainder.toLowerCase(Locale.ROOT).replace('ё', 'е').split("\\s+")) {
            if (SUMMARY_WORDS.contains(w)) {
                summary = true;
            } else if (BALANCE_WORDS.contains(w)) {
                balance = true;
            } else if (!FILLERS.contains(w)) {
                return Query.NONE;
            }
        }
        if (balance) {
            return Query.BALANCE;
        }
        return summary ? Query.SUMMARY : Query.NONE;
    }

    private static String stripFillers(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (!w.isEmpty() && !FILLERS.contains(w.toLowerCase(Locale.ROOT))) {
                sb.append(sb.isEmpty() ? "" : " ").append(w);
            }
        }
        return sb.toString();
    }

    /** Вырезает найденный фрагмент, оставляя шов «;» (он же разделитель позиций для парсера еды). */
    private static String cut(String s, Matcher m) {
        return s.substring(0, m.start()) + " ; " + s.substring(m.end());
    }

    /** Убирает швы и висячие разделители, сохраняя остальную пунктуацию (десятичные запятые). */
    private static String tidy(String s) {
        String r = s.replaceAll("[,\\s]*;[,\\s;]*", " ; ").replaceAll("\\s+", " ");
        return r.replaceAll("^[;,.\\s]+|[;,.\\s]+$", "");
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static Integer steps(String number, String mult) {
        double v = num(number);
        if (mult != null && !mult.isEmpty()) {
            v *= 1000;
        }
        return (int) Math.round(v);
    }
}
