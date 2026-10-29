/// <reference path="../rules.d.ts" />

// ARCH-0003 : le cœur NEXUS est construit en Java simple ; les frameworks applicatifs (Quarkus,
// CDI/Jakarta, Spring, Micronaut, Guice) ne vivent que dans les adaptateurs. La règle contrôle les
// imports et les références qualifiées de core/src/main/java (commentaires et littéraux effacés) et
// les groupId déclarés par core/pom.xml (commentaires XML effacés).

const SOURCES = "core/src/main/java/**/*.java";
const POM_COEUR = "core/pom.xml";

const FRAMEWORK_JAVA =
  /\b(?:io\s*\.\s*quarkus|org\s*\.\s*springframework|io\s*\.\s*micronaut|com\s*\.\s*google\s*\.\s*inject|(?:jakarta|javax)\s*\.\s*(?:enterprise|inject|ws\s*\.\s*rs|interceptor|annotation\s*\.\s*(?:PostConstruct|PreDestroy|Priority)))\b/g;

const FRAMEWORK_POM =
  /<groupId>\s*((?:io\.quarkus|org\.springframework|io\.micronaut|com\.google\.inject|jakarta\.(?:enterprise|inject|ws\.rs|interceptor))[\w.-]*|\$\{quarkus\.platform\.group-id\})\s*<\/groupId>/g;

/** Efface commentaires et, si demandé, littéraux Java (longueur et sauts de ligne conservés). */
function masquerJava(source: string, garderLitteraux = false): string {
  const out = source.split("");
  const effacer = (debut: number, fin: number) => {
    for (let k = debut; k < fin; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") {
      let j = i;
      while (j < n && source[j] !== "\n") j++;
      effacer(i, j);
      i = j;
    } else if (c === "/" && source[i + 1] === "*") {
      const fin = source.indexOf("*/", i + 2);
      const j = fin < 0 ? n : fin + 2;
      effacer(i, j);
      i = j;
    } else if (source.startsWith('"""', i)) {
      let j = i + 3;
      while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 3);
      if (!garderLitteraux) effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      if (!garderLitteraux) effacer(i + 1, j - 1);
      i = j;
    } else {
      i++;
    }
  }
  return out.join("");
}

/** Efface les commentaires XML (longueur et sauts de ligne conservés). */
function masquerXml(source: string): string {
  return source.replace(/<!--[\s\S]*?-->/g, (bloc) => bloc.replace(/[^\r\n]/g, " "));
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "core-imports-no-application-framework": {
      description:
        "Aucune référence à Quarkus, CDI/Jakarta (inject, enterprise, ws.rs), Spring, Micronaut ou Guice dans core/src/main/java",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(FRAMEWORK_JAVA)) {
            ctx.report.violation({
              message: `Framework applicatif « ${trouve[0].replace(/\s+/g, "")} » référencé dans le cœur (ARCH-0003).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Déplacer ce code dans l'adaptateur qui a besoin du framework ; le cœur ne dépend que de contrats Java NEXUS.",
            });
          }
        }
      },
    },
    "core-pom-declares-no-application-framework": {
      description: "core/pom.xml ne déclare aucune dépendance Quarkus, Spring, Micronaut, Guice ou CDI/Jakarta",
      severity: "error",
      async check(ctx) {
        const pom = masquerXml(await ctx.readFile(POM_COEUR));
        for (const trouve of pom.matchAll(FRAMEWORK_POM)) {
          ctx.report.violation({
            message: `Dépendance de framework applicatif « ${trouve[1]} » dans le module cœur (ARCH-0003).`,
            file: POM_COEUR,
            line: ligne(pom, trouve.index ?? 0),
            fix: "Déclarer cette dépendance dans le pom.xml de l'adaptateur concerné, jamais dans core/pom.xml.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
