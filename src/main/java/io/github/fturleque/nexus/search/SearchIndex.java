package io.github.fturleque.nexus.search;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SearchIndex {

    void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths) throws IOException;

    void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException;
}
