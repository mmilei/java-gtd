package com.gtd.util;

import java.text.Normalizer;

/**
 * Shared canonical form for matching user/LLM-provided text against vault data: trimmed,
 * lowercased, diacritics stripped — "Verificación" and "verificacion" must compare equal.
 * Born from a real incident where an unaccented message failed to resolve an accented task
 * title; every new matching path (target resolution, keyword overlap, area vocabulary) must
 * go through here rather than reinventing its own comparison and reintroducing that bug.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String s) {
        return Normalizer.normalize(s.strip().toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
