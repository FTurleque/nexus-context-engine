/// <reference path="../rules.d.ts" />

// ARCH-0044 : NEXUS consomme MINOS par un contrat JSON local lu sur stdin ; MinosCodeIndexImporter
// est un adaptateur pur de contrat qui ne lance aucun processus, et aucun type com.minos n'existe
// dans NEXUS. Deux contrôles (commentaires et littéraux effacés) :
// - minos-import-launches-no-process : aucun lancement de processus (ProcessBuilder,
//   Runtime.exec) dans core/src/main/java/com/nexus/index/minos ;
// - no-minos-types-in-nexus : aucune référence au package com.minos dans les sources Java du dépôt,
//   et aucune dépendance Maven vers un groupId com.minos ou un artifactId minos-* dans les pom.xml.

const SURFACE_MINOS = "core/src/main/java/com/nexus/index/minos/**/*.java";
const SOURCES_JAVA = "**/src/*/java/**/*.java";
const POMS = ["pom.xml", "core/pom.xml", "adapters/rest-quarkus/pom.xml", "adapters/mcp-java/pom.xml", "adapters/assistant-clients/pom.xml"];

const PROCESSUS = /\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(|\bProcessHandle\b/g;
const TYPE_MINOS = /\bcom\s*\.\s*minos\b/g;
const MINOS_POM = /<(groupId|artifactId)>\s*(com\.minos[\w.-]*|minos-[\w.-]+)\s*<\/\1>/g;

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
    "minos-import-launches-no-process": {
      description:
        "La surface d'import MINOS (com.nexus.index.minos) ne lance aucun processus : ni ProcessBuilder, ni Runtime.exec, ni ProcessHandle",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SURFACE_MINOS);
        if (fichiers.length === 0) {
          ctx.report.violation({ message: `Aucune source trouvée sous ${SURFACE_MINOS} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const chemin of fichiers) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(PROCESSUS)) {
            ctx.report.violation({
              message: `Lancement de processus « ${trouve[0].replace(/\s+/g, "")} » dans l'import MINOS (ARCH-0044 : MinosCodeIndexImporter ne lance aucun processus et ne découvre aucun JAR MINOS).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Lire l'export MINOS sur stdin ; l'appelant enchaîne lui-même nexus-export et minos-import.",
            });
          }
        }
      },
    },
    "no-minos-types-in-nexus": {
      description: "Aucun type com.minos dans les sources Java et aucune dépendance Maven vers MINOS dans les pom.xml",
      severity: "error",
      async check(ctx) {
        for (const chemin of await ctx.glob(SOURCES_JAVA)) {
          const code = masquerJava(await ctx.readFile(chemin));
          for (const trouve of code.matchAll(TYPE_MINOS)) {
            ctx.report.violation({
              message: "Référence au package com.minos : NEXUS ne connaît MINOS que par son contrat JSON (ARCH-0044).",
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Mapper le document JSON MINOS vers les modèles NEXUS dans com.nexus.index.minos.",
            });
          }
        }
        for (const chemin of POMS) {
          const pom = masquerXml(await ctx.readFile(chemin));
          for (const trouve of pom.matchAll(MINOS_POM)) {
            ctx.report.violation({
              message: `Dépendance Maven « ${trouve[2]} » vers MINOS (ARCH-0044 : aucune dépendance Java/Maven croisée).`,
              file: chemin,
              line: ligne(pom, trouve.index ?? 0),
              fix: "Consommer MINOS par son export JSON lu sur stdin.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
