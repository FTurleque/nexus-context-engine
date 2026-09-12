import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.search.CandidateType;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import java.nio.file.*;
import java.util.*;

/** Reproduction sans importer externe : JavaParser suffit pour relier une ressource de skill. */
public class ScopeProbe {
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory(Path.of(args[0]).toAbsolutePath(), "scope-");
        Path root = Files.createDirectories(base.resolve("project"));
        Path skill = Files.createDirectories(root.resolve(".agents/skills/audit-helper/scripts"));
        Files.writeString(root.resolve("Root.java"), "package demo; public class Root {}\n");
        Files.writeString(skill.resolve("Helper.java"), "import demo.Root; public class Helper { String marker = \"AUDIT_SKILL_RESOURCE\"; }\n");
        Files.writeString(skill.getParent().resolve("SKILL.md"), "---\nname: audit-helper\ndescription: Unrelated synthetic skill\n---\nSynthetic instructions\n");
        try (var app = NexusApplication.create(new NexusPaths(base.resolve("home")), SemanticSearchConfiguration.disabled())) {
            var project = app.registerProject(root, "audit-scope");
            app.index(project.id(), false, false);
            var bundle = app.context(project.id(), "Root", 2000, Set.of(CandidateType.FILE), Map.of(), true).bundle();
            for (var item : bundle.items()) {
                System.out.println("FILE_ONLY_ITEM=" + item.type() + ":" + item.path());
            }
            System.out.println("FILE_ONLY_SKILL_RESOURCE_LEAK=" + bundle.items().stream().anyMatch(i -> i.content().contains("AUDIT_SKILL_RESOURCE")));
            System.out.println("SKILL_SYMBOL_SEARCH=" + app.search(project.id(), "Helper", 10, false).results().stream()
                    .filter(r -> r.candidate().path().toString().contains(".agents")).count());
        }
    }
}
