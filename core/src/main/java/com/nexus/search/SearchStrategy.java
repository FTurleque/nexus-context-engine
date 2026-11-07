package com.nexus.search;

import com.nexus.project.ProjectDescriptor;
import com.nexus.search.candidate.SearchCandidate;

import java.io.IOException;
import java.util.List;

public interface SearchStrategy {

    List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException;
}
