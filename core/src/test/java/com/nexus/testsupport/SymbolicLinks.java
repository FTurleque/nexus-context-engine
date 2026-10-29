package com.nexus.testsupport;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Création de liens symboliques pour les tests d'évasion de chemin.
 *
 * <p>Quand le filesystem refuse le lien, le test est interrompu (skip) sur un poste de
 * développement, mais échoue dès que le support est exigé : variable d'environnement
 * {@code CI} définie à une valeur autre que {@code false}/{@code 0}, ou propriété système
 * {@value #REQUIRED_PROPERTY}{@code =true}. Un runner de CI sans symlinks ne peut donc pas
 * ignorer ces tests en silence.</p>
 */
public final class SymbolicLinks {

    /** Propriété système qui exige le support des symlinks hors CI. */
    public static final String REQUIRED_PROPERTY = "nexus.test.symlinks.required";

    private SymbolicLinks() {
    }

    /**
     * Crée {@code link} vers {@code target} tel quel, ou interrompt/échoue le test si le
     * filesystem ne le permet pas. Un chemin {@code link} déjà occupé est une erreur du test
     * et échoue toujours.
     */
    public static void createOrSkip(Path link, Path target) {
        createOrSkip(link, target, required(System.getenv(), System.getProperty(REQUIRED_PROPERTY)),
                Files::createSymbolicLink);
    }

    @FunctionalInterface
    interface LinkCreator {
        void create(Path link, Path target) throws IOException;
    }

    static void createOrSkip(Path link, Path target, boolean required, LinkCreator creator) {
        try {
            creator.create(link, target);
        } catch (FileAlreadyExistsException occupied) {
            fail("Le chemin du lien symbolique existe déjà : " + link, occupied);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            String message = "Liens symboliques indisponibles sur ce filesystem : " + unavailable;
            if (required) {
                fail(message + " (support exigé : CI ou -D" + REQUIRED_PROPERTY + "=true)", unavailable);
            }
            Assumptions.abort(message);
        }
    }

    /**
     * Vérifie que le filesystem accepte un lien de {@code probe} vers {@code target}, puis
     * supprime la sonde. Sert aux tests qui créent leurs liens plus tard, dans un callback.
     */
    public static void requireSupport(Path probe, Path target) {
        createOrSkip(probe, target);
        try {
            Files.delete(probe);
        } catch (IOException cleanup) {
            fail("Impossible de supprimer la sonde de lien symbolique " + probe, cleanup);
        }
    }

    static boolean required(Map<String, String> environment, String property) {
        if (Boolean.parseBoolean(property)) {
            return true;
        }
        String ciVariable = environment.get("CI");
        if (ciVariable == null || ciVariable.isBlank()) {
            return false;
        }
        String normalized = ciVariable.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("false") && !normalized.equals("0");
    }
}
