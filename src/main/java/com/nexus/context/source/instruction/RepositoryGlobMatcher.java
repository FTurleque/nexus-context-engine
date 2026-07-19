package com.nexus.context.source.instruction;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class RepositoryGlobMatcher {

    private RepositoryGlobMatcher() {
    }

    static boolean matchesAny(List<String> patterns, List<Path> targetPaths) {
        for (String pattern : patterns) {
            Pattern compiled = Pattern.compile(toRegex(pattern));
            for (Path targetPath : targetPaths) {
                String normalized = InstructionDiscoverySupport.repositoryPath(targetPath);
                if (compiled.matcher(normalized).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String toRegex(String glob) {
        String normalized = glob.trim().replace('\\', '/');
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
            } else if (".()[]{}+$^|".indexOf(current) >= 0) {
                regex.append('\\').append(current);
            } else {
                regex.append(current);
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
