package com.nexus.index.jdt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.Configuration;

/** Composant interne du provider JDT LS. */
final class JdtProcessLauncher {
    static Process start(Configuration configuration, Path projectRoot) throws IOException {
        Path launcher = configuration.launcherJar();
        Path platformConfiguration = configuration.platformConfigurationDirectory();
        Path workspace = configuration.workspaceFor(projectRoot);
        List<String> command = new ArrayList<>();
        command.add(configuration.javaCommand());
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=ERROR");
        command.add("-Xmx1G");
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(launcher.toString());
        command.add("-configuration");
        command.add(platformConfiguration.toString());
        command.add("-data");
        command.add(workspace.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectRoot.toFile());
        builder.environment().remove("CLIENT_PORT");
        builder.environment().remove("CLIENT_HOST");
        return builder.start();
    }
}
