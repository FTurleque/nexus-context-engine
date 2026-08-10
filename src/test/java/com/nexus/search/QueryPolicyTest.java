package com.nexus.search;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPolicyTest {

    @Test
    void trimsAndRejectsMissingOrBlankQueries() {
        assertEquals("hello", QueryPolicy.normalize("  hello  "));
        assertThrows(IllegalArgumentException.class, () -> QueryPolicy.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> QueryPolicy.normalize("   \t\n"));
    }

    @Test
    void acceptsExactAsciiBoundaryAndRejectsNextByte() {
        String exact = "a".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES);
        assertEquals(QueryPolicy.MAX_QUERY_UTF8_BYTES, QueryPolicy.utf8Length(exact));
        assertEquals(exact, QueryPolicy.normalize(exact));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QueryPolicy.normalize(exact + "b"));
        assertTrue(failure.getMessage().contains(String.valueOf(QueryPolicy.MAX_QUERY_UTF8_BYTES)));
    }

    @Test
    void appliesTheBoundaryToUtf8BytesRatherThanJavaCharacters() {
        String twoByteCharacter = "é";
        assertEquals(2, twoByteCharacter.getBytes(StandardCharsets.UTF_8).length);
        String exact = twoByteCharacter.repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2);

        assertEquals(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2, exact.length());
        assertEquals(QueryPolicy.MAX_QUERY_UTF8_BYTES, QueryPolicy.utf8Length(exact));
        assertEquals(exact, QueryPolicy.normalize(exact));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QueryPolicy.normalize(exact + twoByteCharacter));
        assertTrue(failure.getMessage().contains("octets UTF-8"));
    }

    @Test
    void measuresAfterOuterWhitespaceNormalization() {
        String exact = "a".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES);
        assertEquals(exact, QueryPolicy.normalize(" \n" + exact + "\t "));
    }
}
