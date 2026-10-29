/// <reference path="../rules.d.ts" />

// Règle de l'ADR GEN-0031 (packager la CLI dans un JAR autonome).
// Décision : en plus du JAR standard, un JAR autonome attaché avec le classifier `cli` via Maven Shade Plugin ;
// classe principale com.nexus.cli.NexusCli ; les deux JAR nexus-context-engine-<version>[-cli].jar sous target/.
// Contrôlé ici, commentaires XML effacés, dans core/pom.xml : artifactId nexus-context-engine ; une exécution du
// maven-shade-plugin avec shadedArtifactAttached=true (le JAR standard n'est pas remplacé) et
// shadedClassifierName=cli ; Main-Class com.nexus.cli.NexusCli dans le manifeste ombré ; mainClass identique pour
// exec-maven-plugin ; copie des deux JAR dans le target/ racine, où les scripts les cherchent. Contrôle propre à
// Archgate ; le lancement réel du JAR est vérifié par scripts/self-smoke.ps1.

const CORE_POM = "core/pom.xml";
const CLI_MAIN = "core/src/main/java/com/nexus/cli/NexusCli.java";
const MAIN_CLASS = "com.nexus.cli.NexusCli";

function withoutXmlComments(text: string): string {
  return text.replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\r\n]/g, " "));
}

/** Contenu du premier <plugin> dont l'artifactId est donné, ou undefined. */
function pluginBlock(pom: string, artifactId: string): string | undefined {
  for (const match of pom.matchAll(/<plugin>([\s\S]*?)<\/plugin>/g)) {
    if (new RegExp(`<artifactId>\\s*${artifactId}\\s*</artifactId>`).test(match[1])) return match[1];
  }
  return undefined;
}

export default {
  rules: {
    "cli-uber-jar-attached-with-cli-classifier": {
      description:
        "core/pom.xml attache un JAR ombré de classifier cli, de Main-Class com.nexus.cli.NexusCli, à côté du JAR standard",
      severity: "error",
      async check(ctx) {
        const pom = withoutXmlComments(await ctx.readFile(CORE_POM));
        const fail = (message: string, fix: string) => ctx.report.violation({ message, file: CORE_POM, fix });

        const project = pom.replace(/<parent>[\s\S]*?<\/parent>/, "").replace(/<dependencies>[\s\S]*?<\/dependencies>/g, "");
        if (!/<artifactId>\s*nexus-context-engine\s*<\/artifactId>/.test(project.split("<build>")[0])) {
          fail("L'artifactId du cœur n'est plus nexus-context-engine : les JAR nexus-context-engine-<version>[-cli].jar ne sont plus produits.",
            "Rétablir <artifactId>nexus-context-engine</artifactId> ou remplacer GEN-0031.");
        }

        const shade = pluginBlock(pom, "maven-shade-plugin");
        if (shade === undefined) {
          fail("Aucun maven-shade-plugin dans core/pom.xml : le JAR autonome de la CLI n'est plus produit.",
            "Rétablir l'exécution shade du cœur (GEN-0031).");
          return;
        }
        if (!/<shadedArtifactAttached>\s*true\s*<\/shadedArtifactAttached>/.test(shade)) {
          fail("shadedArtifactAttached n'est pas true : le JAR ombré remplacerait le JAR standard.",
            "Écrire <shadedArtifactAttached>true</shadedArtifactAttached>.");
        }
        if (!/<shadedClassifierName>\s*cli\s*<\/shadedClassifierName>/.test(shade)) {
          fail("Le JAR ombré n'a plus le classifier cli.", "Écrire <shadedClassifierName>cli</shadedClassifierName>.");
        }
        if (!new RegExp(`<Main-Class>\\s*${MAIN_CLASS.replace(/\./g, "\\.")}\\s*</Main-Class>`).test(shade)) {
          fail(`Le manifeste du JAR ombré n'a pas Main-Class ${MAIN_CLASS}.`,
            `Déclarer <Main-Class>${MAIN_CLASS}</Main-Class> dans le ManifestResourceTransformer.`);
        }

        const exec = pluginBlock(pom, "exec-maven-plugin");
        if (exec !== undefined && !new RegExp(`<mainClass>\\s*${MAIN_CLASS.replace(/\./g, "\\.")}\\s*</mainClass>`).test(exec)) {
          fail(`exec-maven-plugin ne lance pas ${MAIN_CLASS} : Maven exec et le JAR CLI divergeraient.`,
            `Écrire <mainClass>${MAIN_CLASS}</mainClass>.`);
        }

        for (const suffix of ["", "-cli"]) {
          const copy = new RegExp(
            `<copy\\s+file="\\$\\{project\\.build\\.directory\\}/nexus-context-engine-\\$\\{project\\.version\\}${suffix}\\.jar"\\s+tofile="\\$\\{project\\.basedir\\}/\\.\\./target/nexus-context-engine-\\$\\{project\\.version\\}${suffix}\\.jar"`,
          );
          if (!copy.test(pom)) {
            fail(`Le JAR nexus-context-engine-<version>${suffix}.jar n'est plus copié dans le target/ racine, où les scripts le cherchent.`,
              "Rétablir la copie de l'exécution antrun publish-legacy-root-artifacts.");
          }
        }

        if ((await ctx.glob(CLI_MAIN)).length === 0) {
          ctx.report.violation({ message: `${CLI_MAIN} est introuvable : la classe principale de la CLI a disparu.`, file: CORE_POM });
        }
      },
    },
  },
} satisfies RuleSet;
