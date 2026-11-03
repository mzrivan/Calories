package kz.calories.dish;

import java.util.Locale;

public final class Names {
    private Names() {}

    /** Нормализация: нижний регистр, ё→е, схлопывание пробелов, trim. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }
}
