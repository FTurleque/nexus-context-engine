package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.config.SecurityPolicy;
import com.nexus.context.ContextMaterializationLimits;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.index.scan.ProjectScanLimits;
import com.nexus.index.scip.ScipIndexLimits;
import com.nexus.index.service.ProjectIndexingService;
import com.nexus.security.ProjectFileLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void emptyEnvironmentResolvesTheHistoricalDefaults() {
        NexusConfiguration configuration = NexusApplicationFactory.configuration(paths(), Map.of());

        assertEquals(ProjectFileLimits.DEFAULT_MAX_FILE_SIZE_BYTES, configuration.maxFileSizeBytes());
        assertEquals(ProjectScanLimits.defaults(), configuration.projectScanLimits());
        assertEquals(ContextMaterializationLimits.defaults(), configuration.contextMaterializationLimits());
        assertEquals(ContextDiscoveryLimits.defaults(), configuration.contextDiscoveryLimits());
        assertEquals(ScipIndexLimits.defaults(), new ScipIndexLimits(configuration.maxScipIndexBytes(), configuration.maxScipMessageBytes()));
        assertEquals(2, configuration.maxConcurrentIndexing());
        assertEquals(ProjectIndexingService.DEFAULT_PROVIDER_TIMEOUT, configuration.codeIntelligenceTimeout());
        assertTrue(configuration.jdtConfiguration().isEmpty());
        assertEquals(Map.of(), configuration.securityPosture());
    }

    @Test
    void injectedEnvironmentReachesEachPart() {
        Path jdtHome = temporaryDirectory.resolve("jdtls");
        NexusConfiguration configuration = NexusApplicationFactory.configuration(paths(), Map.of(
                ProjectFileLimits.MAX_FILE_SIZE_ENVIRONMENT_VARIABLE, "1024",
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE, "10",
                ContextMaterializationLimits.MAX_OPENED_FILES_ENVIRONMENT_VARIABLE, "7",
                ContextDiscoveryLimits.ENV_MAX_CANDIDATES, "9",
                "NEXUS_MAX_CONCURRENT_INDEXING", "4",
                ProjectIndexingService.PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE, "30",
                "NEXUS_JDTLS_HOME", jdtHome.toString(),
                "NEXUS_JDTLS_TRUSTED_PROJECT_ROOTS", "trusted-root",
                SecurityPolicy.STRICT_PATH_IO_ENV, "true",
                "UNRELATED_SECRET", "ignored"));

        assertEquals(1024L, configuration.maxFileSizeBytes());
        assertEquals(10, configuration.projectScanLimits().maxFiles());
        assertEquals(7, configuration.contextMaterializationLimits().maxOpenedFiles());
        assertEquals(9, configuration.contextDiscoveryLimits().maxCandidateResources());
        assertEquals(4, configuration.maxConcurrentIndexing());
        assertEquals(Duration.ofSeconds(30), configuration.codeIntelligenceTimeout());
        var jdt = configuration.jdtConfiguration().orElseThrow();
        assertEquals(jdtHome.toAbsolutePath().normalize(), jdt.installationDirectory());
        assertEquals(paths().home().resolve("jdtls-workspaces"), jdt.workspaceRoot());
        assertEquals("trusted-root", jdt.trustedProjectRoots());
        assertEquals(Map.of(SecurityPolicy.STRICT_PATH_IO_ENV, "true"), configuration.securityPosture());
    }

    @Test
    void invalidValueFailsWithTheMessageOfItsLimitClass() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NexusApplicationFactory.configuration(paths(), Map.of("NEXUS_MAX_CONCURRENT_INDEXING", "17")));

        assertEquals("NEXUS_MAX_CONCURRENT_INDEXING doit être compris entre 1 et 16", failure.getMessage());
    }

    @Test
    void semanticConfigurationIsResolvedFromAnInjectedTable() {
        assertTrue(!NexusApplicationFactory.semanticSearchConfiguration(Map.of()).enabled());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NexusApplicationFactory.semanticSearchConfiguration(Map.of("NEXUS_SEMANTIC_PROVIDER", "other")));
        assertTrue(failure.getMessage().startsWith("NEXUS_SEMANTIC_PROVIDER"));
    }

    private NexusPaths paths() {
        return new NexusPaths(temporaryDirectory.resolve("home"));
    }
}
