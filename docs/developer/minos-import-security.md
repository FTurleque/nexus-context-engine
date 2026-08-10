# Sécurité des imports MINOS

L'import MINOS combine deux frontières indépendantes avant de matérialiser un `CodeSymbol`.

## 1. Allowlist canonique de l'index NEXUS

La surcharge utilisée par l'application reçoit l'ensemble des chemins relatifs déjà reconnus par l'index NEXUS. Un symbole ou une relation MINOS n'est accepté que si son `filePath` appartient à cet ensemble après validation syntaxique stricte :

- chemin relatif uniquement ;
- aucun segment `..` ;
- aucune normalisation permettant de masquer une traversée ;
- aucune entrée absente de l'allowlist canonique.

## 2. Revalidation physique juste avant lecture

La validation des plages MINOS doit relire le fichier source afin de vérifier `endLine <= lineCount`. Cette relecture passe désormais par :

1. `ProjectPathGuard.resolve(...)` pour la frontière lexicale ;
2. `ProjectPathGuard.requireRegularFile(...)` pour refuser tout composant symlink sous la racine et confirmer la cible réelle ;
3. `SafeFileIO.readStringNoFollow(...)` pour ouvrir le composant final sans suivre un lien au moment de l'ouverture et conserver les limites de taille communes.

L'allowlist n'est donc pas considérée comme une autorisation permanente de lecture. Si le filesystem change après la sélection canonique — fichier supprimé, fichier remplacé par un symlink ou répertoire ancêtre remplacé par un symlink — l'import échoue de manière déterministe.

## Validation des plages

Pour tout fichier allowlisté référencé par un symbole résolu, le comptage de lignes doit réussir. L'ancienne valeur sentinelle `-1`, qui permettait de poursuivre sans valider `endLine`, n'est plus utilisée sur ce chemin.

Les fichiers canoniques valides conservent le comportement existant ; une plage dépassant le nombre réel de lignes reste rejetée.

## Tests de frontière

La qualification couvre :

- fichier canonique valide ;
- traversal dans l'allowlist ;
- fichier supprimé après sélection ;
- composant final remplacé par un symlink ;
- répertoire ancêtre remplacé par un symlink ;
- validation existante des plages nulles, inversées, hors fichier et à la dernière ligne.

Les tests de symlink sont conditionnés aux capacités de la plateforme ; la qualification Linux fournit la couverture effective des liens symboliques, tandis que Windows conserve la compilation et les autres invariants filesystem supportés par le runner.
