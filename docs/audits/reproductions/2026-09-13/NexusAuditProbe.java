import com.nexus.security.SensitiveContentRedactor;
import java.nio.file.*;
import java.lang.reflect.*;
public class NexusAuditProbe {
  public static void main(String[] args) throws Exception {
    String escaped = "{\"password\":\"AuditPrefix123\\\"VISIBLE_SECRET_SUFFIX\"}";
    String single = "{'password': 'AuditSyntheticPassword98765'}";
    System.out.println("escaped=" + SensitiveContentRedactor.redact(escaped));
    System.out.println("single=" + SensitiveContentRedactor.redact(single));
    Path skill = Files.createTempDirectory("nexus-alias-probe").resolve("audit-skill/SKILL.md");
    Files.createDirectories(skill.getParent());
    StringBuilder yaml = new StringBuilder("---\nname: audit-skill\ndescription: Synthetic metadata audit\na0: &a0 [x, x]\n");
    for (int i = 1; i <= 18; i++) yaml.append("a"+i+": &a"+i+" [*a"+(i-1)+", *a"+(i-1)+"]\n");
    yaml.append("metadata:\n  amplified: *a18\n---\nSynthetic body\n");
    Files.writeString(skill, yaml);
    Class<?> parserClass = Class.forName("com.nexus.context.source.skill.SkillFrontmatterParser");
    Constructor<?> ctor = parserClass.getDeclaredConstructor(); ctor.setAccessible(true);
    Method parse = parserClass.getDeclaredMethod("parse", Path.class); parse.setAccessible(true);
    long start = System.nanoTime();
    Object parsed = parse.invoke(ctor.newInstance(), skill);
    Method metadata = parsed.getClass().getDeclaredMethod("metadata"); metadata.setAccessible(true);
    var values = (java.util.Map<?,?>) metadata.invoke(parsed);
    System.out.println("yamlBytes="+Files.size(skill)+" metadataChars="+values.get("amplified").toString().length()+" elapsedMs="+(System.nanoTime()-start)/1000000);
  }
}
