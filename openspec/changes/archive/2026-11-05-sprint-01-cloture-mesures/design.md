# Design

## Context

Constats fermés du sprint 1 : `AUD-TST-01`, `AUD-TST-03` (#268), `AUD-QUA-06`, `AUD-DEP-20`, `AUD-TST-08` (#266), `AUD-TST-11` (#267) et l'alignement des chemins d'`AUD-DEP-14` (#265). Les checks obligatoires du ruleset de `develop` sont `Windows gate`, `Linux reactor Maven build`, `CodeQL Java analysis`, `OSV new-vulnerability delta gate / osv-scan`, `Build aggregate reactor SBOM`, `OSV aggregate SBOM vulnerability gate / osv-scan` et `SonarCloud Code Analysis`.

## Goals / Non-Goals

**Goals:**
- Fermer `AUD-DEP-30` et la partie restante d'`AUD-DEP-14`.
- Rendre les tests symlink propres sur le système de fichiers.

**Non-Goals:**
- Changer les budgets du benchmark ou son protocole.
- Rédiger le `SECURITY.md` complet d'`AUD-DEP-17` (sprint 10) : seule la section de traitement des alertes est écrite ici.

## Decisions

**D1 — Réglages par le mainteneur.** Les réglages de sécurité et le ruleset sont modifiés à la main par le mainteneur dans GitHub ; aucun outil du dépôt ne les change. La vérification se fait en lecture avec `gh api`.

**D2 — Déclencheur sans filtre, détection dans le job.** `scale-benchmark.yml` garde la liste de chemins surveillés comme source unique (celle que le contrat de #265 compare au wrapper `develop-push`). Une étape initiale calcule le diff de la PR contre sa base, et le job termine en succès avec un message explicite quand aucun chemin surveillé n'est touché. Alternative écartée : un check requis sur un workflow filtré par chemins, qui laisse en attente toute PR hors de ces chemins.

**D3 — Ordre imposé.** Le check n'est ajouté au ruleset qu'après la fusion de D2 et un run vert sur une PR qui ne touche aucun chemin surveillé.

**D4 — Cible externe dans le `@TempDir`.** La racine du projet simulé devient `temporaryDirectory.resolve("project")` et la cible externe `temporaryDirectory.resolve("outside…")`. La cible reste hors du projet simulé, ce qui est le but du test, tout en étant nettoyée par JUnit.

## Risks / Trade-offs

- [Le benchmark tourne désormais sur toute PR] → l'étape de détection conclut en quelques secondes hors des chemins surveillés ; le coût est un démarrage de job.
- [La détection de changements diverge de la liste de chemins] → elle lit la même liste que le contrat anti-dérive ; le contrat est étendu pour la couvrir.
- [Le secret scanning signale des faux positifs dans les fixtures de redaction] → triage documenté dans `SECURITY.md`, et fermeture « used in tests » des alertes concernées.

## Traçabilité

Ces changements sont des plans non réalisés : aucune exigence n'est déclarée couverte. Les cases de `tasks.md` cochées (1.2, 2.1 à 2.3, 2.5, 3.1 à 3.3) indiquent un travail annoncé, dont la preuve n'a pas été revérifiée ici.

| Exigence (`capacité › titre`) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| `repository-security-settings › Les dépendances fusionnées sont surveillées en continu` | Lecture des réglages | D1 (réglages par le mainteneur, vérification en lecture avec `gh api`) | 1.1 | à produire par la tâche 1.1 — `gh api repos/FTurleque/nexus-context-engine --jq '.security_and_analysis'` (tous `enabled`) et `gh api repos/FTurleque/nexus-context-engine/vulnerability-alerts -i` (204) ; case non cochée |
| `repository-security-settings › Les secrets poussés sont bloqués ou signalés` | Lecture des réglages | D1 | 1.1 | à produire par la tâche 1.1 — même lecture `security_and_analysis` (secret scanning et push protection `enabled`) ; case non cochée |
| `repository-security-settings › Le traitement des alertes est documenté` | Faux positif d'une fixture de redaction | Risks / Trade-offs, 3e puce (triage documenté dans `SECURITY.md`) | 1.2 (rédaction), 1.3 (tri des alertes ouvertes) | à produire par la tâche 1.2 — `bash .claude/scripts/contracts.sh` (case cochée, preuve non revérifiée) ; aucun test ne vérifie le contenu de la procédure : test non écrit |
| `scale-benchmark-gate › Le check est produit sur toute PR` | PR hors des chemins surveillés | D2 (déclencheur sans filtre, détection dans le job) | 2.1, 2.3 | à produire par la tâche 2.3 — « Hermetic scale benchmark » conclut en succès en quelques secondes sur une PR de documentation seule (case cochée, preuve non revérifiée) |
| `scale-benchmark-gate › Le check est produit sur toute PR` | PR sur un chemin surveillé | D2 | 2.1 | à produire par la tâche 2.1 — vérification sur la PR elle-même (chemin surveillé touché, benchmark mesuré) (case cochée, preuve non revérifiée) |
| `scale-benchmark-gate › Une seule liste de chemins surveillés` | Dérive de la liste | D2 (liste unique), Risks / Trade-offs, 2e puce | 2.2 | à produire par la tâche 2.2 — extension de `scripts/release/test-operational-doc-contracts.sh`, vérifiée par `bash .claude/scripts/contracts.sh` (case cochée, preuve non revérifiée) ; le cas « chemin ajouté à la détection seule » n'est pas décrit par la tâche : test non écrit |
| `scale-benchmark-gate › Le check est requis sur develop` | Lecture du ruleset | D3 (ordre imposé) | 2.4 | à produire par la tâche 2.4 — `gh api repos/FTurleque/nexus-context-engine/rulesets` puis la règle `required_status_checks` ; case non cochée |
| `test-filesystem-isolation › Les fichiers de test restent dans le répertoire temporaire géré` | Cible externe d'un test symlink | D4 (cible externe dans le `@TempDir`) | 3.1 | à produire par la tâche 3.1 — `./mvnw -B -ntp -pl core test -Dtest='NativeProjectCustomizationDetectorTest,AiSkillsRegistryProviderTest' ...` (case cochée, preuve non revérifiée) |
| `test-filesystem-isolation › Les fichiers de test restent dans le répertoire temporaire géré` | Aucun résidu après le run | D4 | 3.2, 3.3 | à produire par les tâches 3.2 et 3.3 — `git grep -n "temporaryDirectory.resolveSibling" -- core/src/test` vide, puis les mêmes tests avec `CI=true` (cases cochées, preuve non revérifiée) |

Aucun scénario sans tâche (`tâche absente`). La tâche 4.1 (clôture des constats) ne correspond à aucun scénario.

Aucune vue Mermaid n'est ajoutée : le design décrit des réglages GitHub, un ordre de mise en place (D3) et une règle de détection de chemins en une phrase (D2) ; aucune structure ni aucun flux n'est assez détaillé pour qu'un diagramme apporte plus que le texte.
