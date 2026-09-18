import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.security.PublicContextPolicy;
import java.nio.file.*;
import java.util.*;

public class ContextProbe {
    public static void main(String[] args) throws Exception {
        var base = Files.createTempDirectory(Path.of("target/audit-20260914"), "context-").toAbsolutePath();
        var repo = Files.createDirectory(base.resolve("repo"));
        String secret = "auditSyntheticSecret123!";
        Files.writeString(repo.resolve("guide.md"), "# Audit configuration\nExample configuration auditConfig:\n```json\n{\"password\":\"" + secret + "\",\"api_key\":\"" + secret + "\"}\n```\n");
        try (var app = NexusApplication.create(new NexusPaths(base.resolve("home")))) {
            var project = app.registerProject(repo, "audit-fixture");
            var first = app.index(project.id(), false, false);
            var second = app.index(project.id(), false, false);
            var result = app.context(project.id(), "auditConfig", 1000, Set.of(), Map.of(), true);
            var exposed = PublicContextPolicy.expose(result.project(), result.bundle(), "auditConfig");
            System.out.println("fixture=" + base);
            System.out.println("first_changed=" + first.report().changedFiles() + " second_changed=" + second.report().changedFiles());
            System.out.println("items=" + exposed.items().size() + " estimated_tokens=" + exposed.estimatedTokens());
            System.out.println("public_context_secret_present=" + exposed.items().stream().anyMatch(i -> i.content().contains(secret)));
            var again = app.context(project.id(), "auditConfig", 1000, Set.of(), Map.of(), true);
            System.out.println("repeated_context_items_equal=" + result.bundle().items().equals(again.bundle().items()));
            var search = app.search(project.id(), secret, 5, true);
            System.out.println("secret_query_hits=" + search.results().size());
        }
    }
}
