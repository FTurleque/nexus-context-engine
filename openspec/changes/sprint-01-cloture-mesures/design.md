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
