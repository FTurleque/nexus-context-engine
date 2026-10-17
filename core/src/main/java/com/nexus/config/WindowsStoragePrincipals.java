package com.nexus.config;

import com.sun.jna.platform.win32.Advapi32Util;
import java.nio.file.FileSystems;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Résout une fois les trois SID bien connus ; aucun nom fourni par un projet n'est résolu. */
final class WindowsStoragePrincipals {
    private static final System.Logger LOGGER = System.getLogger(WindowsStoragePrincipals.class.getName());
    private WindowsStoragePrincipals() { }

    static Set<String> names() { return Names.VALUE; }

    private static final class Names {
        private static final Set<String> VALUE = load();
    }

    private static Set<String> load() {
        if (!FileSystems.getDefault().getSeparator().equals("\\")) return Set.of();
        try {
            return resolve(sid -> {
                String account = Advapi32Util.getAccountBySid(sid).fqn;
                try {
                    return FileSystems.getDefault().getUserPrincipalLookupService()
                            .lookupPrincipalByName(account).getName();
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("Principal Windows non résolu : " + sid, failure);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            // Aucun fallback par nom traduit présumé. Le mode privé refuse les ACL non prouvées.
            LOGGER.log(System.Logger.Level.WARNING, "Impossible de résoudre les SID système Windows", failure);
            return Set.of();
        }
    }

    static Set<String> resolve(Function<String, String> lookup) {
        Set<String> names = new LinkedHashSet<>();
        for (String sid : Set.of("S-1-5-18", "S-1-5-32-544", "S-1-3-0")) {
            String name = lookup.apply(sid);
            if (name == null || name.isBlank()) throw new IllegalStateException("SID Windows non résolu : " + sid);
            names.add(name.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }
}
