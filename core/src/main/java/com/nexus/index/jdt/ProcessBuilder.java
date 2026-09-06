package com.nexus.index.jdt;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Constructeur de processus JDT LS à environnement explicitement borné.
 *
 * <p>Cette classe package-private porte volontairement le nom simple
 * {@code ProcessBuilder} : le provider JDT du même package l'utilise sans nom
 * qualifié, ce qui centralise la politique d'environnement sans propager les
 * secrets et options JVM arbitraires du processus NEXUS vers JDT LS.</p>
 */
final class ProcessBuilder {

    private static final Set<String> ALLOWED_ENVIRONMENT_VARIABLES = Set.of(
            "PATH",
            "JAVA_HOME",
            "JDK_HOME",
            "HOME",
            "USERPROFILE",
            "HOMEDRIVE",
            "HOMEPATH",
            "TMP",
            "TEMP",
            "TMPDIR",
            "SYSTEMROOT",
            "WINDIR",
            "COMSPEC",
            "PATHEXT",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "USER",
            "USERNAME",
            "LOGNAME",
            "APPDATA",
            "LOCALAPPDATA",
            "XDG_CACHE_HOME",
            "XDG_CONFIG_HOME",
            "MAVEN_HOME",
            "M2_HOME",
            "GRADLE_HOME",
            "GRADLE_USER_HOME");

    private final java.lang.ProcessBuilder delegate;

    ProcessBuilder(List<String> command) {
        delegate = new java.lang.ProcessBuilder(command);
        sanitizeEnvironment(delegate.environment());
    }

    ProcessBuilder directory(File directory) {
        delegate.directory(directory);
        return this;
    }

    Map<String, String> environment() {
        return delegate.environment();
    }

    Process start() throws IOException {
        // Revalide juste avant start() afin qu'aucun appel intermédiaire au Map
        // mutable retourné par environment() ne puisse réintroduire un secret.
        sanitizeEnvironment(delegate.environment());
        return delegate.start();
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name ->
                !ALLOWED_ENVIRONMENT_VARIABLES.contains(name.toUpperCase(Locale.ROOT)));
    }
}
