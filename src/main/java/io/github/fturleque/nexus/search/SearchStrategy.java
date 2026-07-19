package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.util.List;

public interface SearchStrategy {

    List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException;
}
