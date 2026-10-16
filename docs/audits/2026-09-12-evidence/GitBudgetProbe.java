import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.context.source.git.*;
import com.nexus.project.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Observe le nombre de DiffEntry déjà matérialisés avant le premier débit NEXUS. */
public class GitBudgetProbe {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]).toAbsolutePath(), "git-budget-");
        try (Git git = Git.init().setDirectory(root.toFile()).call();
             ObjectInserter inserter = git.getRepository().newObjectInserter();
             RevWalk walk = new RevWalk(git.getRepository())) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, "synthetic\n".getBytes());
            ObjectId emptyTree = inserter.insert(new TreeFormatter());
            TreeFormatter tree = new TreeFormatter();
            for (int i = 0; i < 10000; i++) tree.append(String.format(Locale.ROOT, "file%05d.js", i), FileMode.REGULAR_FILE, blob);
            ObjectId largeTree = inserter.insert(tree);
            ObjectId before = commit(inserter, emptyTree, null);
            ObjectId after = commit(inserter, largeTree, before);
            inserter.flush();
            var budget = new ContextDiscoveryLimits(1, 10, 1024, 15000).newBudget();
            var project = new ProjectDescriptor(UUID.randomUUID(), "audit-git", root, ProjectSourceType.LOCAL,
                    Set.of(), Set.of(), null, IndexStatus.READY);
            var query = new GitContextQuery(project, "synthetic", List.of(Path.of("file00000.js")), true, budget);
            try (DiffFormatter formatter = new DiffFormatter(OutputStream.nullOutputStream()) {
                @Override public List<DiffEntry> scan(RevTree a, RevTree b) throws IOException {
                    List<DiffEntry> entries = super.scan(a, b);
                    System.out.println("GIT_ENTRIES_MATERIALIZED_BEFORE_NEXUS_CHECK=" + entries.size());
                    return entries;
                }
            }) {
                formatter.setRepository(git.getRepository());
                formatter.setDetectRenames(true);
                var method = LocalGitContextSourceProvider.class.getDeclaredMethod("changedPaths", DiffFormatter.class, RevCommit.class, RevCommit.class, GitContextQuery.class);
                method.setAccessible(true);
                try { method.invoke(null, formatter, walk.parseCommit(before), walk.parseCommit(after), query); }
                catch (java.lang.reflect.InvocationTargetException e) { System.out.println("GIT_NEXUS_REJECTION=" + e.getCause().getClass().getSimpleName()); }
                System.out.println("GIT_CONFIGURED_VISIT_LIMIT=1");
                System.out.println("GIT_WORK=" + budget.snapshot());
            }
        }
    }
    static ObjectId commit(ObjectInserter inserter, ObjectId tree, ObjectId parent) throws IOException {
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        if (parent != null) commit.setParentId(parent);
        PersonIdent author = new PersonIdent("Audit", "audit@example.invalid");
        commit.setAuthor(author);
        commit.setCommitter(author);
        commit.setMessage("Synthetic audit fixture");
        return inserter.insert(commit);
    }
}
