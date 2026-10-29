/// <reference path="../rules.d.ts" />

// Règle de l'ADR GEN-0002 (compiler le cœur avec Java 21 comme niveau minimal).
// Décision : le pom.xml fixe <maven.compiler.release>21</maven.compiler.release> ; le cœur reste compatible Java 21.
// Contrôlé ici, commentaires XML effacés : le POM racine énonce exactement une propriété maven.compiler.release, de
// valeur 21, et ni le POM racine ni celui du cœur (core/pom.xml) ne fixent un autre niveau (autre
// maven.compiler.release, maven.compiler.source/target, ou <release>/<source> du maven-compiler-plugin).
// L'enforcer (requireJavaVersion [21,)) borne le JDK de build, pas le niveau de bytecode : ce contrôle est propre à
// Archgate.

const ROOT_POM = "pom.xml";
const CORE_POM = "core/pom.xml";
const EXPECTED = "21";

/** Efface les commentaires XML (longueur et sauts de ligne conservés). */
function withoutXmlComments(text: string): string {
  return text.replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\r\n]/g, " "));
}

// Fonctions communes : copie conforme de gabarit/fonctions-communes.ts (contrôlée par GEN-0057).

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "core-compiles-with-release-21": {
      description:
        "Le POM racine fixe maven.compiler.release à 21 et ni lui ni core/pom.xml ne fixent un autre niveau de compilation",
      severity: "error",
      async check(ctx) {
        const root = withoutXmlComments(await ctx.readFile(ROOT_POM));
        const releases = [...root.matchAll(/<maven\.compiler\.release>\s*([^<]*?)\s*<\/maven\.compiler\.release>/g)];
        if (releases.length !== 1) {
          ctx.report.violation({
            message: `${ROOT_POM} doit déclarer exactement une propriété maven.compiler.release ; trouvé ${releases.length}.`,
            file: ROOT_POM,
            fix: `Garder une seule ligne <maven.compiler.release>${EXPECTED}</maven.compiler.release> dans les propriétés du POM racine.`,
          });
        }
        for (const release of releases) {
          if (release[1] !== EXPECTED) {
            ctx.report.violation({
              message: `maven.compiler.release vaut « ${release[1]} » : GEN-0002 fixe le niveau de compilation du cœur à ${EXPECTED}.`,
              file: ROOT_POM,
              line: ligne(root, release.index ?? 0),
              fix: `Revenir à ${EXPECTED} ; un relèvement global du niveau minimal exige un nouvel ADR qui remplace GEN-0002.`,
            });
          }
        }

        const overrides =
          /<maven\.compiler\.(?:source|target|testRelease|testSource|testTarget)>|<release>|<source>\s*\d/g;
        for (const pom of [ROOT_POM, CORE_POM]) {
          const text = withoutXmlComments(await ctx.readFile(pom));
          for (const match of text.matchAll(overrides)) {
            ctx.report.violation({
              message: `${match[0]} dans ${pom} : le niveau de compilation du cœur ne se règle que par maven.compiler.release=${EXPECTED}.`,
              file: pom,
              line: ligne(text, match.index ?? 0),
              fix: "Retirer ce réglage ; un adaptateur qui exige un runtime plus récent l'isole dans son propre POM avec un ADR spécifique.",
            });
          }
          if (pom === CORE_POM) {
            for (const match of text.matchAll(/<maven\.compiler\.release>/g)) {
              ctx.report.violation({
                message: `${CORE_POM} redéfinit maven.compiler.release : le cœur hérite du niveau ${EXPECTED} du POM racine.`,
                file: CORE_POM,
                line: ligne(text, match.index ?? 0),
                fix: "Supprimer la redéfinition dans core/pom.xml.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
