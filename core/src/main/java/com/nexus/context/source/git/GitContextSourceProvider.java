package com.nexus.context.source.git;

import java.io.IOException;

/**
 * Source locale de contexte Git. Les implémentations doivent rester en lecture seule.
 */
public interface GitContextSourceProvider {

    String id();

    GitContextResult discover(GitContextQuery query) throws IOException;
}
