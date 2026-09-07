package com.nexus.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextPolicyTest {

    @Test
    void boundsFuzzyTermsByCountAndLength() {
        String longTerm = "x".repeat(SymbolSearchStrategy.MAX_QUERY_TERM_CHARS + 200);
        StringBuilder query = new StringBuilder(longTerm);
        for (int index = 0; index < 100; index++) {
            query.append(' ').append("term").append(index);
        }

        List<String> terms = SearchText.boundedTerms(
                query.toString(),
                SymbolSearchStrategy.MAX_QUERY_TERMS,
                SymbolSearchStrategy.MAX_QUERY_TERM_CHARS);

        assertEquals(SymbolSearchStrategy.MAX_QUERY_TERMS, terms.size());
        assertTrue(terms.stream().allMatch(term ->
                term.length() <= SymbolSearchStrategy.MAX_QUERY_TERM_CHARS));
        assertEquals(
                "x".repeat(SymbolSearchStrategy.MAX_QUERY_TERM_CHARS),
                terms.getFirst());
    }

    @Test
    void removesDuplicatesAfterTermTruncation() {
        int maxChars = 4;
        List<String> terms = SearchText.boundedTerms("abcdefgh abcdzzzz unique", 8, maxChars);

        assertEquals(List.of("abcd", "uniq"), terms);
    }

    @Test
    void pathScoreCanReuseTheAlreadyBoundedTerms() {
        List<String> terms = List.of("order", "service");

        assertEquals(1.0d, SearchText.pathScore("src/order/OrderService.java", terms), 0.000001d);
    }
}
