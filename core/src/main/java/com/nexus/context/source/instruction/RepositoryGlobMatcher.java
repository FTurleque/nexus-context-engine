package com.nexus.context.source.instruction;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class RepositoryGlobMatcher {

    /** Borne du cache des motifs compilés ; au-delà, le motif le moins récemment utilisé est évincé. */
    static final int MAX_CACHED_PATTERNS = 256;

    private static final Map<String, Pattern> COMPILED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
            return size() > MAX_CACHED_PATTERNS;
        }
    };

    private RepositoryGlobMatcher() {
    }

    static boolean matchesAny(List<String> patterns, List<Path> targetPaths) {
        for (String pattern : patterns) {
            Pattern compiled = compiled(pattern);
            for (Path targetPath : targetPaths) {
                String normalized = InstructionDiscoverySupport.repositoryPath(targetPath);
                if (compiled.matcher(normalized).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Motif compilé pour un glob source, partagé entre requêtes dans un cache LRU borné. */
    static Pattern compiled(String glob) {
        synchronized (COMPILED) {
            return COMPILED.computeIfAbsent(glob, source -> Pattern.compile(toRegex(source)));
        }
    }

    static int cachedPatternCount() {
        synchronized (COMPILED) {
            return COMPILED.size();
        }
    }

    private static String toRegex(String glob) {
        String normalized = glob.trim();
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < normalized.length() && normalized.charAt(index + 1) == '*';
                if (doubleStar) {
                    boolean followedBySlash = index + 2 < normalized.length() && normalized.charAt(index + 2) == '/';
                    if (followedBySlash) {
                        regex.append("(?:.*/)?");
                        index += 2;
                    } else {
                        regex.append(".*");
                        index++;
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else if ("\\.()[]{}+$^|".indexOf(current) >= 0) {
                regex.append('\\').append(current);
            } else {
                regex.append(current);
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
