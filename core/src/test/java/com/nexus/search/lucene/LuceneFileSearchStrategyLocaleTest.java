package com.nexus.search.lucene;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuceneFileSearchStrategyLocaleTest {

    @Test
    void pathScoringIsStableUnderTurkishDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Method pathScore = LuceneFileSearchStrategy.class
                    .getDeclaredMethod("pathScore", String.class, String.class);
            pathScore.setAccessible(true);

            double score = (double) pathScore.invoke(null, "src/INDEX.md", "index");

            assertEquals(1.0d, score);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
