package com.nexus.context.source.instruction;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryGlobMatcherTest {

    @Test
    void sameGlobIsCompiledOnceAcrossQueries() {
        String glob = "src/**/reuse-" + System.nanoTime() + "/*.java";

        Pattern first = RepositoryGlobMatcher.compiled(glob);
        Pattern second = RepositoryGlobMatcher.compiled(glob);

        assertSame(first, second);
        assertTrue(first.matcher(glob.replace("**/", "a/b/").replace("*.java", "App.java")).matches());
    }

    @Test
    void cacheNeverExceedsItsBoundAndEvictsTheLeastRecentlyUsedGlob() {
        String prefix = "bound-" + System.nanoTime() + "/";
        Pattern oldest = RepositoryGlobMatcher.compiled(prefix + "oldest/*.md");
        for (int index = 0; index < RepositoryGlobMatcher.MAX_CACHED_PATTERNS * 2; index++) {
            RepositoryGlobMatcher.compiled(prefix + index + "/**");
            assertTrue(RepositoryGlobMatcher.cachedPatternCount() <= RepositoryGlobMatcher.MAX_CACHED_PATTERNS);
        }

        assertNotSame(oldest, RepositoryGlobMatcher.compiled(prefix + "oldest/*.md"));
        assertTrue(RepositoryGlobMatcher.cachedPatternCount() <= RepositoryGlobMatcher.MAX_CACHED_PATTERNS);
    }

    @Test
    void cachedPatternsKeepTheGlobSemantics() {
        List<String> patterns = List.of("docs/*.md", "core/**/Test?.java");

        assertTrue(RepositoryGlobMatcher.matchesAny(patterns, List.of(Path.of("core", "a", "b", "TestA.java"))));
        assertTrue(RepositoryGlobMatcher.matchesAny(patterns, List.of(Path.of("docs", "README.md"))));
        assertFalse(RepositoryGlobMatcher.matchesAny(patterns, List.of(Path.of("docs", "sub", "README.md"))));
        assertFalse(RepositoryGlobMatcher.matchesAny(patterns, List.of(Path.of("core", "TestAB.java"))));
    }
}
