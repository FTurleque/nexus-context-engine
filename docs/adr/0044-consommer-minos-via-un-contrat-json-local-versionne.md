# ADR-0044 — Consommer MINOS via un contrat JSON local versionné

- Statut : accepted
- Date : 2026-07-24
- Décideurs : projet NEXUS
- Lié à : MINOS M13 / NEXUS issue #11

## Contexte et problème

MINOS est le moteur de Code Intelligence de l’écosystème : symboles, relations, provenance et preuves. NEXUS est le moteur de Context Intelligence : index local, recherche, ranking, sélection et construction d’un contexte sous budget.

NEXUS doit pouvoir exploiter la connaissance MINOS sans la réimplémenter et sans déplacer dans MINOS la responsabilité du contexte final.

Une dépendance Java directe est exclue : NEXUS conserve Java 21 alors que MINOS est compilé et validé avec Java 24.

## Facteurs de décision

- préserver Java 21 pour NEXUS et Java 24 pour MINOS ;
- ne pas introduire de dépendance Maven croisée ;
- garder l’intégration locale-first et explicitement déclenchée ;
- conserver `SearchService`, le ranking et `DefaultContextBuilder` comme responsables du contexte ;
- permettre une évolution versionnée du contrat MINOS ;
- ne pas lancer un runtime Java 24 depuis le cœur NEXUS ;
- ne pas faire dépendre l’indexation NEXUS normale de la présence de MINOS ;
- traiter les chemins du document MINOS comme des données non fiables ;
- échouer explicitement en cas de contrat incompatible ou de mauvais projet.

## Options envisagées

### Dépendance Java/Maven directe vers MINOS

Rejetée. Le bytecode Java 24 n’est pas une dépendance acceptable pour un cœur NEXUS Java 21 et les modèles deviendraient couplés.

### Serveur MCP ou HTTP MINOS

Non retenu pour M13. Le besoin est un transfert local déterministe de snapshot, sans couche réseau ni protocole de service supplémentaire.

### Processus MINOS lancé par NEXUS

Rejeté dans la conception finale. Cette approche ajoutait une orchestration de runtime Java 24 dans NEXUS et une surface sécurité inutile autour de l’exécution de processus.

### Export MINOS explicite puis import NEXUS via stdin

Retenu.

```text
MINOS Java 24
  nexus-export --root <project>
          |
          | JSON stdout
          v
NEXUS Java 21
  minos-import <project> < stdin
          |
          v
IndexRepository -> SearchService -> ranking -> ContextBuilder
```

Les deux moteurs restent des processus indépendants. Le shell, l’IDE, JARVIS ou un script d’exploitation peut chaîner les deux commandes sans que NEXUS ne pilote MINOS.

## Décision

NEXUS adopte `MinosCodeIndexImporter` comme **adaptateur pur de contrat JSON**. Il ne lance aucun processus et ne découvre aucun JAR MINOS.

L’import est explicite :

```powershell
# Java 24 / MINOS
java -Dminos.home=<home> -jar <minos-all.jar> nexus-export --root <project> > minos-export.json

# Java 21 / NEXUS
Get-Content -Raw minos-export.json | nexus minos-import <project>
```

La CLI NEXUS lit le payload sur stdin avec une borne de **128 MiB**, le valide puis appelle `IndexRepository.replaceExternalCodeIntelligence(...)` avec `sourceProvider=minos`.

L’indexation normale NEXUS continue à utiliser ses analyseurs et importers existants, notamment SCIP. Un import MINOS n’est jamais déclenché implicitement par `nexus index`.

Seuls les symboles et relations `RESOLVED` dont la sémantique existe dans le modèle NEXUS sont convertis. Les kinds non représentables sont ignorés, jamais reclassés arbitrairement.

Pour les chemins :

1. NEXUS construit une allow-list à partir des fichiers locaux réellement présents sous la racine projet de confiance ;
2. le `filePath` fourni par le JSON est uniquement normalisé lexicalement ;
3. un chemin absolu, remontant ou absent de l’allow-list est ignoré ;
4. le `filePath` externe n’est jamais utilisé comme argument d’une API d’I/O.

## Conséquences positives

- aucun couplage binaire Java 21/24 ;
- aucun type `com.minos` dans NEXUS ;
- aucun processus enfant lancé par NEXUS ;
- aucun réseau requis ;
- aucune configuration de chemin JAR/runtime MINOS dans NEXUS ;
- import entièrement explicite et reproductible ;
- réutilisation de SQLite, recherche, ranking et ContextBuilder existants ;
- NEXUS reste pleinement utilisable sans MINOS ;
- le contrat peut évoluer par version sans modifier immédiatement le modèle NEXUS.

## Conséquences négatives acceptées

- l’appelant doit orchestrer explicitement l’export puis l’import ;
- les faits MINOS ne sont rafraîchis que lorsqu’un nouvel import est demandé ;
- le mapping NEXUS ne représente pas encore toute la richesse du contrat MINOS ;
- un replay inter-dépôt reste requis pour qualifier les évolutions de contrat.

## Confirmation

La décision est considérée respectée lorsque :

- aucun `ProcessBuilder` ou lancement MINOS n’existe dans la surface M13 NEXUS ;
- `minos-import` lit uniquement stdin et borne le payload ;
- les versions/producteur/root invalides sont refusés ;
- les chemins absolus et remontants sont ignorés sans accès I/O induit par le JSON ;
- un replay avec le vrai export MINOS importe `GreetingPort` avec provenance `minos` ;
- une recherche NEXUS retrouve ce symbole ;
- la baseline Java 21 et les tests existants restent verts ;
- le Quality Gate sécurité reste acquis.

## Conditions de réexamen

Réexaminer cette décision si :

- NEXUS relève officiellement son niveau Java au niveau de MINOS ;
- un contrat local standardisé de l’écosystème remplace l’échange JSON ;
- un besoin mesuré exige un rafraîchissement automatique avec un mécanisme d’orchestration situé hors du cœur NEXUS.
