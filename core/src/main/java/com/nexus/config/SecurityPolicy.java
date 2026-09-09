package com.nexus.config;

/** Configuration de sécurité partagée par les primitives et les adaptateurs. */
public final class SecurityPolicy {
    public static final String STRICT_PATH_IO_PROPERTY = "nexus.requireStrictPathIo";
    public static final String STRICT_PATH_IO_ENV = "NEXUS_REQUIRE_STRICT_PATH_IO";

    private SecurityPolicy() { }

    public static boolean requireStrictPathIo() {
        return booleanSetting(STRICT_PATH_IO_PROPERTY, STRICT_PATH_IO_ENV);
    }

    public static boolean booleanSetting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        if (value == null || value.isBlank() || "false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        throw new IllegalStateException(environment + " doit valoir true ou false");
    }

    public static void requireHardenedStorageAndTraversal() {
        if (!NexusPaths.requirePrivateStorage()) {
            throw new IllegalStateException("Une exposition hardened exige "
                    + NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE + "=true");
        }
        if (!requireStrictPathIo()) {
            throw new IllegalStateException("Une exposition hardened exige " + STRICT_PATH_IO_ENV + "=true");
        }
    }
}
