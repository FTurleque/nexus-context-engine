package com.nexus.api;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies that a declared remote REST exposure mode is backed by an effective Quarkus transport configuration.
 */
final class NexusRestTransportPolicy {

    static final String INSECURE_REQUESTS_PROPERTY = "quarkus.http.insecure-requests";
    static final String TLS_CONFIGURATION_NAME_PROPERTY = "quarkus.http.tls-configuration-name";
    static final String LEGACY_CERTIFICATE_FILES_PROPERTY = "quarkus.http.ssl.certificate.files";
    static final String LEGACY_KEY_FILES_PROPERTY = "quarkus.http.ssl.certificate.key-files";
    static final String LEGACY_KEYSTORE_FILE_PROPERTY = "quarkus.http.ssl.certificate.key-store-file";
    static final String PROXY_ADDRESS_FORWARDING_PROPERTY = "quarkus.http.proxy.proxy-address-forwarding";
    static final String TRUSTED_PROXIES_PROPERTY = "quarkus.http.proxy.trusted-proxies";

    private static final List<String> CATCH_ALL_TRUSTED_PROXIES = List.of(
            "*",
            "0.0.0.0/0",
            "::/0",
            "0:0:0:0:0:0:0:0/0");

    private NexusRestTransportPolicy() {
    }

    static void validateSecureNonLoopbackExposure(String exposureMode) {
        validateSecureNonLoopbackExposure(exposureMode, ConfigProvider.getConfig());
    }

    static void validateSecureNonLoopbackExposure(String exposureMode, Config config) {
        String normalizedMode = exposureMode == null ? "" : exposureMode.trim().toLowerCase(Locale.ROOT);
        if (!NexusRestSecurity.isSecureNonLoopbackExposureMode(normalizedMode)) {
            throw new IllegalArgumentException("Unsupported secure REST exposure mode: " + exposureMode);
        }

        String insecureRequests = configured(config, INSECURE_REQUESTS_PROPERTY).orElse("enabled");
        if (!"disabled".equalsIgnoreCase(insecureRequests)) {
            throw new IllegalStateException(
                    normalizedMode + " exige " + INSECURE_REQUESTS_PROPERTY
                            + "=disabled afin qu'aucun listener HTTP en clair ne soit exposé");
        }

        if (!hasServerTlsMaterial(config)) {
            throw new IllegalStateException(
                    normalizedMode + " exige une configuration TLS serveur Quarkus effective "
                            + "(quarkus.tls.* key-store ou quarkus.http.ssl.certificate.*)");
        }

        if ("reverse-proxy-https".equals(normalizedMode)) {
            validateReverseProxyBoundary(config);
        }
    }

    private static void validateReverseProxyBoundary(Config config) {
        boolean forwarding = configured(config, PROXY_ADDRESS_FORWARDING_PROPERTY)
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (!forwarding) {
            throw new IllegalStateException(
                    "reverse-proxy-https exige " + PROXY_ADDRESS_FORWARDING_PROPERTY + "=true");
        }

        String trustedProxies = configured(config, TRUSTED_PROXIES_PROPERTY)
                .orElseThrow(() -> new IllegalStateException(
                        "reverse-proxy-https exige une liste explicite " + TRUSTED_PROXIES_PROPERTY));

        List<String> configuredProxies = splitCommaSeparated(trustedProxies);
        if (configuredProxies.isEmpty()) {
            throw new IllegalStateException(
                    "reverse-proxy-https exige au moins un proxy explicite dans " + TRUSTED_PROXIES_PROPERTY);
        }
        for (String proxy : configuredProxies) {
            if (CATCH_ALL_TRUSTED_PROXIES.stream().anyMatch(proxy::equalsIgnoreCase)) {
                throw new IllegalStateException(
                        TRUSTED_PROXIES_PROPERTY + " refuse une plage de confiance globale: " + proxy);
            }
        }
    }

    private static boolean hasServerTlsMaterial(Config config) {
        if (configured(config, LEGACY_KEYSTORE_FILE_PROPERTY).isPresent()) {
            return true;
        }
        if (configured(config, LEGACY_CERTIFICATE_FILES_PROPERTY).isPresent()
                && configured(config, LEGACY_KEY_FILES_PROPERTY).isPresent()) {
            return true;
        }

        String tlsConfigurationName = configured(config, TLS_CONFIGURATION_NAME_PROPERTY).orElse(null);
        String keyStorePrefix = tlsConfigurationName == null
                ? "quarkus.tls.key-store."
                : "quarkus.tls." + tlsConfigurationName + ".key-store.";

        boolean pathConfigured = false;
        boolean pemCertificateConfigured = false;
        boolean pemKeyConfigured = false;
        for (String propertyName : config.getPropertyNames()) {
            if (!propertyName.startsWith(keyStorePrefix) || configured(config, propertyName).isEmpty()) {
                continue;
            }
            String suffix = propertyName.substring(keyStorePrefix.length());
            if (suffix.endsWith(".path")) {
                pathConfigured = true;
            }
            if (suffix.startsWith("pem.") && suffix.endsWith(".cert")) {
                pemCertificateConfigured = true;
            }
            if (suffix.startsWith("pem.") && suffix.endsWith(".key")) {
                pemKeyConfigured = true;
            }
        }
        return pathConfigured || (pemCertificateConfigured && pemKeyConfigured);
    }

    private static Optional<String> configured(Config config, String propertyName) {
        return config.getOptionalValue(propertyName, String.class)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    private static List<String> splitCommaSeparated(String value) {
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return List.copyOf(entries);
    }
}
