/// <reference path="../rules.d.ts" />

// Règles de l'ADR BE-0043 (recherche fédérée au-dessus du SearchService). Contrôles propres à
// Archgate : les tests de FederatedSearchService vérifient le comportement, pas les dépendances.
// - Décision : fédérer plusieurs recherches projet-locales au-dessus du SearchService existant ; chaque
//   projet garde son index Lucene et ses données SQLite ; NexusApplication expose searchAcrossProjects.
//   Contrôle federated-search-built-on-search-service : FederatedSearchService référence SearchService
//   et n'accède directement ni à Lucene (org.apache.lucene), ni à la persistance (com.nexus.persistence,
//   java.sql), ni au réseau (java.net, java.net.http) ; NexusApplication déclare searchAcrossProjects.
// - Décision : le ContextBuilder reste mono-projet.
//   Contrôle context-builder-stays-single-project : ContextBuilder.java et DefaultContextBuilder.java ne
//   référencent aucun type Federated* ni FederatedSearchService.
// Commentaires et littéraux effacés avant la recherche.

const FEDERATED = "core/src/main/java/com/nexus/search/FederatedSearchService.java";
const APPLICATION = "core/src/main/java/com/nexus/application/NexusApplication.java";
const BUILDERS = [
  "core/src/main/java/com/nexus/context/ContextBuilder.java",
  "core/src/main/java/com/nexus/context/DefaultContextBuilder.java",
];
const DIRECT_ACCESS = /\borg\s*\.\s*apache\s*\.\s*lucene\b|\bcom\s*\.\s*nexus\s*\.\s*persistence\b|\bjava\s*\.\s*sql\b|\bjava\s*\.\s*net\b/g;

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
    "federated-search-built-on-search-service": {
      description:
        "FederatedSearchService délègue au SearchService sans accès direct à Lucene, SQLite ou au réseau ; NexusApplication expose searchAcrossProjects",
      severity: "error",
      async check(ctx) {
        const code = masquerJava(await ctx.readFile(FEDERATED));
        if (!/\bSearchService\b/.test(code)) {
          ctx.report.violation({
            message: "FederatedSearchService ne référence pas SearchService : la fédération doit s'appuyer sur la recherche projet-locale existante (BE-0043).",
            file: FEDERATED,
            fix: "Injecter SearchService et lui déléguer la recherche de chaque projet.",
          });
        }
        for (const match of code.matchAll(DIRECT_ACCESS)) {
          ctx.report.violation({
            message: `Accès direct « ${match[0].replace(/\s+/g, "")} » dans la fédération : chaque projet est recherché par son SearchService (BE-0043).`,
            file: FEDERATED,
            line: ligne(code, match.index ?? 0),
            fix: "Passer par SearchService ; l'index Lucene et les données SQLite restent isolés par projet.",
          });
        }
        const application = masquerJava(await ctx.readFile(APPLICATION));
        if (!/\bpublic\s+[\w<>?,\s]+\s+searchAcrossProjects\s*\(/.test(application)) {
          ctx.report.violation({
            message: "NexusApplication ne déclare pas d'opération publique searchAcrossProjects(...) (BE-0043).",
            file: APPLICATION,
            fix: "Exposer la fédération par NexusApplication.searchAcrossProjects(...) pour que les adaptateurs ne la réimplémentent pas.",
          });
        }
      },
    },
    "context-builder-stays-single-project": {
      description: "ContextBuilder et DefaultContextBuilder ne référencent aucun type fédéré : le ContextBuilder reste mono-projet",
      severity: "error",
      async check(ctx) {
        for (const file of BUILDERS) {
          const code = masquerJava(await ctx.readFile(file));
          for (const match of code.matchAll(/\bFederated\w*/g)) {
            ctx.report.violation({
              message: `« ${match[0]} » dans ${file.split("/").pop()} : le ContextBuilder reste mono-projet (BE-0043).`,
              file,
              line: ligne(code, match.index ?? 0),
              fix: "Composer plusieurs ContextBuilder mono-projet depuis l'extérieur ; un contexte multi-projet relève d'une décision séparée.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
