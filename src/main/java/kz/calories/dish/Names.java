package kz.calories.dish;

import java.util.Locale;

public final class Names {
    private Names() {}

    /** Нормализация: нижний регистр, ё→е, десятичная запятая→точка, схлопывание пробелов, trim. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("(\\d),(\\d)", "$1.$2")
                .replaceAll("\\s+", " ").trim();
    }
}
