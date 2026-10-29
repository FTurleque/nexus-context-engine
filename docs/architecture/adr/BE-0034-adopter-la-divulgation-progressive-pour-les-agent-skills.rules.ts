/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0034 (divulgation progressive des Agent Skills). Contrôle propre à Archgate : les
// tests vérifient qu'un script listé n'est pas exécuté sur un cas donné, pas l'absence de code capable
// de le lancer.
// - Décision : NEXUS ne lance jamais les fichiers de scripts/ d'un skill ; l'activation inventorie les
//   ressources et « ne rien exécuter ».
// - Contrôle : les sources de com.nexus.context.source.skill ne lancent aucun processus
//   (ProcessBuilder, Runtime.exec), n'utilisent aucun moteur de script (javax.script) et n'ouvrent
//   aucun fichier par le bureau (java.awt.Desktop). Commentaires et littéraux effacés.

const SOURCES = "core/src/main/java/com/nexus/context/source/skill/**/*.java";

const FORBIDDEN: { pattern: RegExp; what: string }[] = [
  { pattern: /\bProcessBuilder\b/g, what: "lancement de processus (ProcessBuilder)" },
  { pattern: /\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g, what: "lancement de processus (Runtime.exec)" },
  { pattern: /\bProcessHandle\b/g, what: "pilotage de processus (ProcessHandle)" },
  { pattern: /\bjavax\s*\.\s*script\b|\bScriptEngine(?:Manager)?\b/g, what: "moteur de script (javax.script)" },
  { pattern: /\bjava\s*\.\s*awt\s*\.\s*Desktop\b|\bDesktop\s*\.\s*getDesktop\s*\(/g, what: "ouverture par le bureau (java.awt.Desktop)" },
];

/** Efface les commentaires et, sauf si garderLitteraux, les littéraux (longueur et sauts de ligne conservés). */
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

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "skill-resources-never-executed": {
      description:
        "Les sources de com.nexus.context.source.skill ne lancent ni processus, ni moteur de script, ni ouverture par le bureau",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(SOURCES)).sort();
        if (files.length === 0) {
          ctx.report.violation({ message: `Aucune source sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const file of files) {
          const code = masquerJava(await ctx.readFile(file));
          for (const { pattern, what } of FORBIDDEN) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `${what} dans la découverte des skills : « ${match[0].replace(/\s+/g, " ")} ». BE-0034 : NEXUS n'exécute rien.`,
                file,
                line: ligne(code, match.index ?? 0),
                fix: "Inventorier la ressource comme métadonnée (SkillResourceDescriptor) et laisser son exécution au consommateur final.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
