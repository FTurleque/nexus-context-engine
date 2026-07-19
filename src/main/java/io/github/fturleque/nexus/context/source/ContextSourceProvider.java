package io.github.fturleque.nexus.context.source;

import java.io.IOException;
import java.util.List;

/**
 * Port de découverte des sources de contexte natives d'un projet.
 */
public interface ContextSourceProvider {

    String id();

    List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException;
}
