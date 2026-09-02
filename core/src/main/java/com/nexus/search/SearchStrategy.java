package com.nexus.search;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.util.List;

public interface SearchStrategy {

    List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException;
}
