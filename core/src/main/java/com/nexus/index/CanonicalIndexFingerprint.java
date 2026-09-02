package com.nexus.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Empreinte déterministe de l'état canonique des fichiers d'un projet.
 *
 * <p>L'empreinte ne dépend que des métadonnées canoniques déjà calculées par
 * NEXUS et permet aux index dérivés de prouver qu'ils correspondent encore au
 * même ensemble de fichiers/contenus sans relire le workspace.</p>
 */
public final class CanonicalIndexFingerprint {

    private CanonicalIndexFingerprint() {
    }

    public static String fromScannedFiles(Collection<ScannedFile> files) {
        Objects.requireNonNull(files, "files");
        MessageDigest digest = sha256();
        files.stream()
                .sorted(Comparator.comparing(ScannedFile::relativePath))
                .forEach(file -> update(
                        digest,
                        file.relativePath(),
                        file.contentHash(),
                        file.language(),
                        file.category().name()));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String fromIndexedFiles(Map<String, IndexedFile> files) {
        Objects.requireNonNull(files, "files");
        MessageDigest digest = sha256();
        files.values().stream()
                .sorted(Comparator.comparing(IndexedFile::relativePath))
                .forEach(file -> update(
                        digest,
                        file.relativePath(),
                        file.contentHash(),
                        file.language(),
                        file.category().name()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible", exception);
        }
    }

    private static void update(MessageDigest digest, String... values) {
        for (String value : values) {
            digest.update(Objects.requireNonNull(value, "fingerprint value")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        digest.update((byte) '\n');
    }
}
