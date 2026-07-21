package com.nexus.search;

public final class SearchSignals {

    public static final String LEXICAL = "lexicalScore";
    public static final String SYMBOL_EXACT = "symbolExactScore";
    public static final String SYMBOL_FUZZY = "symbolFuzzyScore";
    public static final String PATH = "pathScore";
    public static final String GRAPH = "graphScore";
    public static final String GIT_RECENCY = "gitRecencyScore";
    public static final String SEMANTIC = "semanticScore";

    private SearchSignals() {
    }
}
