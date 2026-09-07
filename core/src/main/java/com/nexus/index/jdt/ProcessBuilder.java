package com.nexus.index.jdt;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Constructeur de processus JDT LS à environnement explicitement borné.
 *
 * <p>Cette classe package-private porte volontairement le nom simple
 * {@code ProcessBuilder} : le provider JDT du même package l'utilise sans nom
 * qualifié, ce qui centralise la politique d'environnement sans propager les
 * secrets et options JVM arbitraires du processus NEXUS vers JDT LS.</p>
 *
 * <p>JDT LS importe les métadonnées Maven/Gradle du workspace et constitue donc
 * une frontière d'exécution pour un repository non fiable. Le subprocess ne
 * peut démarrer que lorsque sa racine canonique est explicitement listée dans
 * {@value #TRUSTED_PROJECT_ROOTS_ENVIRONMENT_VARIABLE}. La comparaison porte sur
 * le chemin réel exact : autoriser un parent n'autorise pas implicitement tous
 * ses descendants.</p>
 */
final class ProcessBuilder {

    static final String TRUSTED_PROJECT_ROOTS_ENVIRONMENT_VARIABLE =
            "NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS";

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
        // JDT LS peut importer/évaluer des métadonnées Maven/Gradle. Vérifier la
        // confiance immédiatement avant le démarrage évite qu'une mutation
        // intermédiaire du working directory contourne la frontière.
        requireTrustedProjectDirectory(
                delegate.directory(),
                System.getenv(TRUSTED_PROJECT_ROOTS_ENVIRONMENT_VARIABLE));

        // Revalide juste avant start() afin qu'aucun appel intermédiaire au Map
        // mutable retourné par environment() ne puisse réintroduire un secret.
        sanitizeEnvironment(delegate.environment());
        return delegate.start();
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name ->
                !ALLOWED_ENVIRONMENT_VARIABLES.contains(name.toUpperCase(Locale.ROOT)));
    }

    static void requireTrustedProjectDirectory(File directory, String configuredRoots) throws IOException {
        if (directory == null) {
            throw new IOException("JDT LS exige un répertoire projet explicite");
        }
        if (configuredRoots == null || configuredRoots.isBlank()) {
            throw untrustedProject(directory.toPath());
        }

        Path projectRoot = directory.toPath().toRealPath();
        String separator = Pattern.quote(File.pathSeparator);
        for (String configuredRoot : configuredRoots.split(separator, -1)) {
            if (configuredRoot.isBlank()) {
                continue;
            }
            try {
                Path trustedRoot = Path.of(configuredRoot.strip())
                        .toAbsolutePath()
                        .normalize()
                        .toRealPath();
                if (projectRoot.equals(trustedRoot)) {
                    return;
                }
            } catch (IOException | InvalidPathException ignored) {
                // Une entrée absente, invalide ou non canonisable n'élargit jamais
                // implicitement la confiance vers une autre racine.
            }
        }
        throw untrustedProject(projectRoot);
    }

    private static IOException untrustedProject(Path projectRoot) {
        return new IOException(
                "Analyse JDT LS refusée pour le projet non approuvé " + projectRoot
                        + ". Ajoutez sa racine canonique exacte à "
                        + TRUSTED_PROJECT_ROOTS_ENVIRONMENT_VARIABLE
                        + " uniquement si vous faites confiance à ses fichiers de build Maven/Gradle.");
    }
}
