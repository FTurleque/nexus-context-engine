package com.nexus.cli;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.FederatedScopePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexusCliFederatedScopePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cliRejectsOversizedExplicitUuidScopeBeforeProjectResolution() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        Method resolveScope = NexusCli.class.getDeclaredMethod(
                "resolveProjectScope", NexusApplication.class, String.class);
        resolveScope.setAccessible(true);

        List<UUID> ids = new ArrayList<>(101);
        for (int index = 1; index <= 101; index++) {
            ids.add(new UUID(0L, index));
        }
        String selectors = String.join(",", ids.stream().map(UUID::toString).toList());

        InvocationTargetException invocation = assertThrows(
                InvocationTargetException.class,
                () -> resolveScope.invoke(null, application, selectors));
        IllegalArgumentException failure = assertInstanceOf(
                IllegalArgumentException.class,
                invocation.getCause());

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, failure.getMessage());
    }
}
