import com.nexus.config.NexusPaths;
import com.nexus.search.semantic.lucene.*;
import com.nexus.search.semantic.*;
import com.nexus.index.FileCategory;
import java.nio.file.*;
import java.util.*;
public class NexusSemanticAuditProbe {
 public static void main(String[] args) throws Exception {
  NexusPaths paths = new NexusPaths(Files.createTempDirectory("nexus-semantic-audit"));
  UUID project = UUID.randomUUID();
  try (var writer = new LuceneSemanticSearchIndex(paths,2); var reader = new PersistentLuceneSemanticSearchIndex(paths,2)) {
   writer.rebuild(project, List.of(new SemanticVectorDocument("before.java",FileCategory.SOURCE,"before",new float[]{1,0})));
   System.out.println("before="+reader.search(project,new float[]{1,0},1));
   writer.rebuild(project,List.of(new SemanticVectorDocument("after.java",FileCategory.SOURCE,"after",new float[]{1,0})));
   System.out.println("fresh="+writer.search(project,new float[]{1,0},1));
   System.out.println("cached="+reader.search(project,new float[]{1,0},1));
  }
 }
}
