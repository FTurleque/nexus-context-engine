package com.nexus.index.jdt;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Selects the newest Equinox launcher using OSGi numeric version components. */
final class EquinoxLauncherSelector {

    private static final Pattern LAUNCHER_PATTERN = Pattern.compile(
            "^org\.eclipse\.equinox\.launcher_(\d+)\.(\d+)\.(\d+)(?:\.(.*))?\.jar$");

    private EquinoxLauncherSelector() {
    }

    static Optional<Path> latest(Stream<Path> files) {
        return files
                .filter(Files::isRegularFile)
                .map(EquinoxLauncherSelector::candidate)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(Candidate::version)
                        .thenComparing(candidate -> candidate.path().getFileName().toString()))
                .map(Candidate::path);
    }

    private static Optional<Candidate> candidate(Path path) {
        String name = path.getFileName().toString();
        Matcher matcher = LAUNCHER_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            Version version = new Version(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    matcher.group(4) == null ? "" : matcher.group(4));
            return Optional.of(new Candidate(path, version));
        } catch (NumberFormatException malformedVersion) {
            return Optional.empty();
        }
    }

    private record Candidate(Path path, Version version) {
    }

    private record Version(
            BigInteger major,
            BigInteger minor,
            BigInteger micro,
            String qualifier) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int comparison = major.compareTo(other.major);
            if (comparison != 0) {
                return comparison;
            }
            comparison = minor.compareTo(other.minor);
            if (comparison != 0) {
                return comparison;
            }
            comparison = micro.compareTo(other.micro);
            if (comparison != 0) {
                return comparison;
            }
            return qualifier.compareTo(other.qualifier);
        }
    }
}
