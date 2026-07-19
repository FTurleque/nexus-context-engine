package io.github.fturleque.nexus.index;

import java.util.Objects;

public record IndexedSymbol(String relativePath, CodeSymbol symbol) {

    public IndexedSymbol {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(symbol, "symbol");
    }
}
