/// <reference path="../rules.d.ts" />

// Règle de l'ADR BE-0035 (contexte Git local borné). Contrôle propre à Archgate : les tests Git couvrent
// des cas de lecture, pas l'absence d'appel distant ou d'écriture dans le code.
// - Décision : le provider est local et basé sur JGit ; les données sont lues depuis le repository
//   local uniquement. Confirmation : aucun appel réseau ni mutation Git n'est effectué.
// - Contrôle : les sources de com.nexus.context.source.git n'appellent aucune commande JGit distante
//   (fetch, pull, push, lsRemote, clone, Transport) ni aucune commande ou API qui modifie le repository
//   (commit, checkout, add, rm, reset, merge, rebase, stash, tag, branche, cherry-pick, revert, clean,
//   gc, RefUpdate, ObjectInserter, verrou du DirCache). Commentaires et littéraux effacés.

const SOURCES = "core/src/main/java/com/nexus/context/source/git/**/*.java";

const FORBIDDEN: { pattern: RegExp; what: string }[] = [
  {
    // Commandes de porcelaine JGit : Git#fetch(), Git#commit()… s'appellent sans argument.
    pattern:
      /\.\s*(?:fetch|pull|push|lsRemote|commit|checkout|add|rm|reset|merge|rebase|stashCreate|stashApply|stashDrop|tag|tagDelete|branchCreate|branchDelete|branchRename|cherryPick|revert|clean|gc|apply|notesAdd|notesRemove|submoduleAdd|submoduleUpdate|submoduleInit)\s*\(\s*\)/g,
    what: "commande JGit distante ou mutante",
  },
  { pattern: /\bGit\s*\.\s*(?:cloneRepository|lsRemoteRepository|init)\s*\(/g, what: "commande JGit distante ou de création" },
  { pattern: /\bTransport\s*\.\s*open\b|\bimport\s+org\s*\.\s*eclipse\s*\.\s*jgit\s*\.\s*transport\s*\.\s*Transport\s*;/g, what: "transport Git distant" },
  { pattern: /\b(?:updateRef|newObjectInserter|lockDirCache)\s*\(/g, what: "écriture dans le repository Git" },
  { pattern: /\bRefUpdate\b/g, what: "mise à jour de référence Git (RefUpdate)" },
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
    "git-context-reads-local-repository-only": {
      description:
        "Les sources de com.nexus.context.source.git n'appellent ni commande Git distante ni commande qui modifie le repository",
      severity: "error",
      async check(ctx) {
        const files = (await ctx.glob(SOURCES)).sort();
        if (!files.some((file) => file.endsWith("/LocalGitContextSourceProvider.java"))) {
          ctx.report.violation({ message: `LocalGitContextSourceProvider.java introuvable sous ${SOURCES} : la règle ne peut rien vérifier.` });
          return;
        }
        for (const file of files) {
          const code = masquerJava(await ctx.readFile(file));
          for (const { pattern, what } of FORBIDDEN) {
            for (const match of code.matchAll(pattern)) {
              ctx.report.violation({
                message: `${what} dans le contexte Git : « ${match[0].replace(/\s+/g, " ")} ». BE-0035 : les données sont lues depuis le repository local uniquement.`,
                file,
                line: ligne(code, match.index ?? 0),
                fix: "Lire l'état local (RevWalk, TreeWalk, status, diff) sans réseau ni écriture.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
