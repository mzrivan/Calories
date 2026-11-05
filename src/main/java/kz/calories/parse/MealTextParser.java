package kz.calories.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import kz.calories.meal.MealType;

/**
 * Разбор сообщения как списка «блюдо + вес»: «Хлеб 13, картошка 300», «70 йогурт, 7 карамель»,
 * «Хлеб 26 масло 8 сыр масдам 25», «хлебец на 30ккал». Консервативен: при малейшей неоднозначности
 * возвращает ok=false, и сообщение уходит в LLM. Чистая функция, без Spring и БД.
 */
public final class MealTextParser {

    /** mealType — приём пищи из ключевых слов («на обед», «завтрак:»); OTHER, если слов нет. */
    public record ParsedMessage(boolean ok, List<ParsedItem> items, String reason, MealType mealType) {
        static ParsedMessage fail(String reason) {
            return new ParsedMessage(false, List.of(), reason, MealType.OTHER);
        }
    }

    private static final int MAX_LEN = 500;
    private static final int MAX_ITEMS = 15;

    private static final Pattern FAT = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:%|процент\\p{L}*)?\\s*жирност\\p{L}*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GLUE = Pattern.compile("(?<=\\d)(?=\\p{L})");
    private static final Pattern SEGMENTS = Pattern.compile(
            "\\s*[;\\n]+\\s*|,\\s+|,$|\\s+плюс\\s+|^плюс\\s+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Время («50 минут», «1 час», «40 мин») и слова про активность — это не еда, отдаём LLM-агенту. */
    private static final Pattern ACTIVITY = Pattern.compile(
            "\\d\\s*(?:мин\\p{L}*|час\\p{L}*|ч)(?![\\p{L}])|тренировк\\p{L}*|активност\\p{L}*|\\bкардио\\b|\\bбегал\\p{L}*|\\bпробежк\\p{L}*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUM =Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern PCT = Pattern.compile("\\d+(?:[.,]\\d+)?%");

    private static final Set<String> STOP = Set.of("ещё", "еще", "плюс", "на", "примерно", "около", "~", "≈", "съел", "съела", "выпил", "выпила",
            "поел", "поела", "скушал", "скушала", "запиши", "добавь", "я");
    private static final Set<String> UNIT_G = Set.of("г", "гр", "г.", "гр.", "грамм", "грамма", "граммов", "мл", "мл.");
    private static final Set<String> UNIT_KG = Set.of("кг", "кг.");
    private static final Set<String> UNIT_KCAL = Set.of("ккал", "кал", "ккал.");
    /** Слова-уточнения: название из одних таких слов — признак слипшихся позиций («сухие 172 готовые 440»). */
    private static final Set<String> MODIFIERS = Set.of("сухие", "сухой", "сухая", "готовые", "готовый", "готовая",
            "вареный", "вареные", "варёный", "варёные", "жареный", "жареные", "сырой", "сырые", "свежий", "свежие");

    private enum Kind { WORD, NUM, PCT, KCAL }

    private record El(Kind kind, String text, double value) {}

    private MealTextParser() {}

    public static ParsedMessage parse(String text) {
        if (text == null || text.isBlank()) {
            return ParsedMessage.fail("пусто");
        }
        MealType.Extracted meal = MealType.extract(text.trim());
        if (meal.ambiguous()) {
            return ParsedMessage.fail("несколько приёмов пищи — нужен разбор LLM");
        }
        String t = meal.text();
        if (t.length() > MAX_LEN) {
            return ParsedMessage.fail("слишком длинное");
        }
        if (t.contains("(") || t.contains(")") || t.contains(":") || t.contains("?") || t.contains("/")) {
            return ParsedMessage.fail("скобки/двоеточие/вопрос — нужен разбор LLM");
        }
        if (ACTIVITY.matcher(t).find()) {
            return ParsedMessage.fail("активность/время — нужен разбор LLM");
        }
        t = FAT.matcher(t).replaceAll("$1%");
        t = GLUE.matcher(t).replaceAll(" ");
        List<ParsedItem> items = new ArrayList<>();
        for (String seg : SEGMENTS.split(t)) {
            if (seg.isBlank()) {
                continue;
            }
            String err = parseSegment(seg.trim(), items);
            if (err != null) {
                return ParsedMessage.fail(err + ": «" + seg.trim() + "»");
            }
        }
        if (items.isEmpty()) {
            return ParsedMessage.fail("нет позиций");
        }
        if (items.size() > MAX_ITEMS) {
            return ParsedMessage.fail("слишком много позиций");
        }
        return new ParsedMessage(true, items, null, meal.type());
    }

    /** null = успех (позиции добавлены), иначе причина отказа. */
    private static String parseSegment(String seg, List<ParsedItem> out) {
        List<El> els = tokenize(seg);
        int kcalCount = 0;
        int plain = 0;
        for (El e : els) {
            if (e.kind() == Kind.KCAL) {
                kcalCount++;
            } else if (e.kind() == Kind.NUM) {
                plain++;
            }
        }
        if (kcalCount > 1) {
            return "несколько значений ккал";
        }
        if (kcalCount == 1) {
            if (plain > 0) {
                return "ккал вместе с граммами";
            }
            String name = nameOf(els);
            double kcal = els.stream().filter(e -> e.kind() == Kind.KCAL).findFirst().orElseThrow().value();
            if (!validName(name) || kcal <= 0) {
                return "нет названия";
            }
            out.add(ParsedItem.ofKcal(name, kcal));
            return null;
        }
        if (plain == 0) {
            return "нет веса";
        }
        if (plain == 1) {
            return addGrams(nameOf(els), els.stream().filter(e -> e.kind() == Kind.NUM).findFirst().orElseThrow().value(), out);
        }
        // цепочка позиций без запятых
        boolean leading = els.get(0).kind() == Kind.NUM;
        List<List<El>> chunks = new ArrayList<>();
        List<El> cur = new ArrayList<>();
        for (El e : els) {
            if (leading) {
                if (e.kind() == Kind.NUM && !cur.isEmpty()) {
                    chunks.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(e);
            } else {
                cur.add(e);
                if (e.kind() == Kind.NUM) {
                    chunks.add(cur);
                    cur = new ArrayList<>();
                }
            }
        }
        if (!cur.isEmpty()) {
            if (leading || chunks.isEmpty()) {
                chunks.add(cur);
            } else { // слова после последнего числа — уточнение к последней позиции
                chunks.get(chunks.size() - 1).addAll(cur);
            }
        }
        for (List<El> chunk : chunks) {
            double grams = chunk.stream().filter(e -> e.kind() == Kind.NUM).findFirst().orElseThrow().value();
            String err = addGrams(nameOf(chunk), grams, out);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static String addGrams(String name, double grams, List<ParsedItem> out) {
        if (!validName(name)) {
            return "не удалось выделить название";
        }
        if (grams < 1 || (grams < 10 && grams != Math.rint(grams))) {
            return "неясное число (вес или жирность?)";
        }
        out.add(ParsedItem.ofGrams(name, grams));
        return null;
    }

    private static boolean validName(String name) {
        if (name.isBlank() || !name.matches(".*\\p{L}{2}.*")) {
            return false;
        }
        for (String w : name.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!MODIFIERS.contains(w) && !PCT.matcher(w).matches()) {
                return true;
            }
        }
        return false; // только уточнения/проценты
    }

    private static String nameOf(List<El> els) {
        StringBuilder sb = new StringBuilder();
        for (El e : els) {
            if (e.kind() == Kind.WORD || e.kind() == Kind.PCT) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(e.text());
            }
        }
        return sb.toString().replaceAll("^[\\s\\p{Punct}&&[^%]]+|[\\s\\p{Punct}&&[^%]]+$", "").trim();
    }

    private static List<El> tokenize(String seg) {
        List<El> out = new ArrayList<>();
        for (String raw : seg.trim().split("\\s+")) {
            String tok = raw.replaceAll("^[\"'«».,!]+|[\"'«»!]+$", "");
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.isEmpty() || STOP.contains(low)) {
                continue;
            }
            if (UNIT_G.contains(low)) {
                continue;
            }
            if (UNIT_KG.contains(low)) {
                int last = out.size() - 1;
                if (last >= 0 && out.get(last).kind() == Kind.NUM) {
                    out.set(last, new El(Kind.NUM, out.get(last).text(), out.get(last).value() * 1000));
                }
                continue;
            }
            if (UNIT_KCAL.contains(low)) {
                int last = out.size() - 1;
                if (last >= 0 && out.get(last).kind() == Kind.NUM) {
                    out.set(last, new El(Kind.KCAL, out.get(last).text(), out.get(last).value()));
                }
                continue;
            }
            if (PCT.matcher(tok).matches()) {
                out.add(new El(Kind.PCT, tok.replace(',', '.'), 0));
            } else if (NUM.matcher(tok).matches()) {
                out.add(new El(Kind.NUM, tok, Double.parseDouble(tok.replace(',', '.'))));
            } else {
                out.add(new El(Kind.WORD, tok, 0));
            }
        }
        return out;
    }
}
