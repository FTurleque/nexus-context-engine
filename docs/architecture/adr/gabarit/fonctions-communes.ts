// Fonctions communes des règles Archgate du registre : version canonique.
//
// Le scanner de sécurité d'Archgate refuse tout import local dans un fichier *.rules.ts : une règle
// ne peut pas partager de module. Chaque règle qui en a besoin recopie donc ces fonctions, sous ces
// noms et à l'identique, en ne gardant que celles qu'elle utilise. La règle
// rule-helpers-match-template de GEN-0057 compare chaque copie à ce fichier et échoue au moindre
// écart : une correction se fait ici, puis dans toutes les copies.
//
// Elles préparent le texte lu par ctx.readFile avant une recherche par expression régulière :
// commentaires (et, par défaut, littéraux) remplacés par des espaces, retours à la ligne conservés,
// pour qu'un motif cité dans un commentaire ou une chaîne ne déclenche rien et que les numéros de
// ligne restent exacts.

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

function masquerCommentaires(source: string): string {
  return masquerJava(source, true);
}

function masquerXml(source: string): string {
  return source.replace(/<!--[\s\S]*?-->/g, (bloc) => bloc.replace(/[^\r\n]/g, " "));
}

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}
