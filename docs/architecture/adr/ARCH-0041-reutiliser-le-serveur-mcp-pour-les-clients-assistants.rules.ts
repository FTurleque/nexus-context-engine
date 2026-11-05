/// <reference path="../rules.d.ts" />

// ARCH-0041 : adapters/assistant-clients reste autonome, ne génère que des commandes ou fragments de
// configuration, ne modifie aucun fichier de l'utilisateur et ne crée aucun chemin métier vers le
// cœur. Deux contrôles :
// - assistant-clients-independent-of-engine : adapters/assistant-clients/pom.xml ne dépend ni du
//   cœur (nexus-context-engine) ni du serveur MCP (nexus-mcp-java) ; ses sources n'importent aucun
//   package com.nexus hors de com.nexus.integrations ; ni core/pom.xml ni adapters/mcp-java/pom.xml
//   ne dépendent de nexus-assistant-clients (commentaires XML et Java effacés, littéraux Java effacés) ;
// - assistant-clients-write-no-file : ses sources principales n'écrivent, ne créent, ne copient, ne
//   déplacent ni ne suppriment aucun fichier ; seule l'ouverture de stdout par
//   new FileOutputStream(FileDescriptor.out) est admise.

const POM_INTEGRATIONS = "adapters/assistant-clients/pom.xml";
const POMS_INDEPENDANTS = ["core/pom.xml", "adapters/mcp-java/pom.xml"];
const SOURCES = "adapters/assistant-clients/src/main/java/**/*.java";

const MOTEUR_POM = /<artifactId>\s*(nexus-context-engine|nexus-mcp-java)\s*<\/artifactId>/g;
const INTEGRATIONS_POM = /<artifactId>\s*(nexus-assistant-clients)\s*<\/artifactId>/g;
const IMPORT_NEXUS = /^\s*import\s+(?:static\s+)?(com\s*\.\s*nexus\s*\.\s*[\w.\s*]+?)\s*;/gm;

const ECRITURES: { motif: RegExp; quoi: string }[] = [
  {
    motif:
      /\bFiles\s*(?:\.|::)\s*(?:write|writeString|newOutputStream|newBufferedWriter|createFile|createDirector(?:y|ies)|createTemp(?:File|Directory)|create(?:Symbolic)?Link|delete|deleteIfExists|copy|move|setAttribute|setPosixFilePermissions|setLastModifiedTime|setOwner)\b/g,
    quoi: "écriture ou modification de fichier (java.nio.file.Files)",
  },
  {
    motif: /\bimport\s+static\s+java\s*\.\s*nio\s*\.\s*file\s*\.\s*Files\s*\.\s*[\w*]+\s*;/g,
    quoi: "import statique de java.nio.file.Files (masque les appels d'écriture)",
  },
  { motif: /\bnew\s+(?:FileWriter|RandomAccessFile)\b/g, quoi: "écriture de fichier (FileWriter ou RandomAccessFile)" },
  {
    motif: /\bnew\s+FileOutputStream\s*\((?!\s*FileDescriptor\s*\.\s*(?:out|err)\s*\))/g,
    quoi: "écriture de fichier (FileOutputStream hors stdout/stderr)",
  },
  { motif: /\b(?:Asynchronous)?FileChannel\s*(?:\.|::)\s*open\b/g, quoi: "ouverture d'un canal de fichier" },
  { motif: /\.\s*(?:mkdirs?|createNewFile|renameTo|deleteOnExit|delete)\s*\(\s*\)/g, quoi: "modification de fichier (java.io.File)" },
];

/** Efface commentaires et, si demandé, littéraux Java (longueur et sauts de ligne conservés). */
// Fonctions communes : copie conforme de gabarit/fonctions-communes.ts (contrôlée par GEN-0057).

type Jeton =
  | { type: "commentaire"; fin: number }
  | { type: "litteral"; fin: number; delimiteur: number }
  | { type: "code"; fin: number };

function finDeJeton(source: string, i: number): Jeton {
  const n = source.length;
  const c = source[i];
  if (c === "/" && source[i + 1] === "/") {
    const fin = source.indexOf("\n", i);
    return { type: "commentaire", fin: fin < 0 ? n : fin };
  }
  if (c === "/" && source[i + 1] === "*") {
    const fin = source.indexOf("*/", i + 2);
    return { type: "commentaire", fin: fin < 0 ? n : fin + 2 };
  }
  if (source.startsWith('"""', i)) {
    let j = i + 3;
    while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
    return { type: "litteral", fin: Math.min(n, j + 3), delimiteur: 3 };
  }
  if (c === '"' || c === "'") {
    let j = i + 1;
    while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
    return { type: "litteral", fin: Math.min(n, j + 1), delimiteur: 1 };
  }
  return { type: "code", fin: i + 1 };
}

function masquerJava(source: string, garderLitteraux = false): string {
  const out = source.split("");
  const effacer = (debut: number, fin: number) => {
    for (let k = debut; k < fin; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  let i = 0;
  while (i < source.length) {
    const jeton = finDeJeton(source, i);
    if (jeton.type === "commentaire") effacer(i, jeton.fin);
    else if (jeton.type === "litteral" && !garderLitteraux) effacer(i + jeton.delimiteur, jeton.fin - jeton.delimiteur);
    i = jeton.fin;
  }
  return out.join("");
}

function masquerXml(source: string): string {
  return source.replace(/<!--[\s\S]*?-->/g, (bloc) => bloc.replace(/[^\r\n]/g, " "));
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

/** Efface les commentaires XML (longueur et sauts de ligne conservés). */
async function sources(ctx: RuleContext): Promise<{ chemin: string; code: string }[]> {
  const fichiers = await ctx.glob(SOURCES);
  if (fichiers.length === 0) {
    ctx.report.violation({ message: `Aucune source trouvée sous ${SOURCES} : la règle ne peut rien vérifier.` });
  }
  const resultat: { chemin: string; code: string }[] = [];
  for (const chemin of fichiers) resultat.push({ chemin, code: masquerJava(await ctx.readFile(chemin)) });
  return resultat;
}

export default {
  rules: {
    "assistant-clients-independent-of-engine": {
      description:
        "adapters/assistant-clients ne dépend ni du cœur ni du serveur MCP, n'importe que com.nexus.integrations, et aucun d'eux ne dépend de lui",
      severity: "error",
      async check(ctx) {
        const pom = masquerXml(await ctx.readFile(POM_INTEGRATIONS));
        for (const trouve of pom.matchAll(MOTEUR_POM)) {
          ctx.report.violation({
            message: `adapters/assistant-clients dépend de « ${trouve[1]} » : nouveau chemin vers le moteur (ARCH-0041).`,
            file: POM_INTEGRATIONS,
            line: ligne(pom, trouve.index ?? 0),
            fix: "Le module ne génère que des commandes et fragments de configuration du serveur MCP existant ; retirer la dépendance.",
          });
        }
        for (const chemin of POMS_INDEPENDANTS) {
          const autre = masquerXml(await ctx.readFile(chemin));
          for (const trouve of autre.matchAll(INTEGRATIONS_POM)) {
            ctx.report.violation({
              message: `${chemin} dépend de « ${trouve[1]} » : le cœur et le serveur MCP restent indépendants du module d'intégration (ARCH-0041).`,
              file: chemin,
              line: ligne(autre, trouve.index ?? 0),
              fix: "Retirer la dépendance vers nexus-assistant-clients.",
            });
          }
        }
        for (const { chemin, code } of await sources(ctx)) {
          for (const trouve of code.matchAll(IMPORT_NEXUS)) {
            const cible = trouve[1].replace(/\s+/g, "");
            if (cible.startsWith("com.nexus.integrations.")) continue;
            ctx.report.violation({
              message: `Import « ${cible} » hors de com.nexus.integrations dans adapters/assistant-clients (ARCH-0041).`,
              file: chemin,
              line: ligne(code, trouve.index ?? 0),
              fix: "Ne pas appeler le moteur depuis ce module : les clients passent par le serveur MCP STDIO.",
            });
          }
        }
      },
    },
    "assistant-clients-write-no-file": {
      description:
        "Les sources principales de adapters/assistant-clients n'écrivent, ne créent, ne déplacent ni ne suppriment aucun fichier (sortie sur stdout uniquement)",
      severity: "error",
      async check(ctx) {
        for (const { chemin, code } of await sources(ctx)) {
          for (const { motif, quoi } of ECRITURES) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} : « ${trouve[0].replace(/\s+/g, " ")} ». ARCH-0041 : la génération ne modifie aucun fichier de l'utilisateur.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Émettre la commande ou le fragment de configuration sur stdout ; l'utilisateur l'applique lui-même.",
              });
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
