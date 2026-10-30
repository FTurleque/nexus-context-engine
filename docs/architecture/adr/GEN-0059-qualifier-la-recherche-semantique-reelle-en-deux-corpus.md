---
id: GEN-0059
title: "Qualifier la recherche sémantique réelle en deux corpus, hors du chemin de release"
domain: general
rules: false
---

# Qualifier la recherche sémantique réelle en deux corpus, hors du chemin de release

- **Statut** : Proposed
- **Date** : 2026-10-30
- **Origine** : audit du 2026-10-17, constat `AUD-DEP-19` ; changement OpenSpec `sprint-02-outillage-scripts-workflows`
- **Complète** : [BE-0014](BE-0014-rendre-la-recherche-semantique-optionnelle.md)

## Context

[BE-0014](BE-0014-rendre-la-recherche-semantique-optionnelle.md) rend la recherche sémantique optionnelle et n'admet un provider d'embeddings que s'il apporte un gain mesuré. Cette mesure est faite par `.github/workflows/semantic-search-qualification.yml`, qui lance `RealSemanticSearchBenchmarkTest` contre un runtime Ollama épinglé par SHA-256 et un modèle dont le manifeste est verrouillé, puis applique les seuils `recallAt3 ≥ 0,25`, `hitAt3 ≥ 0,33`, `mrrAt3 ≥ 0,20` et une régression maximale de 0,10 par rapport au ranking sans embeddings.

Le benchmark indexe le dépôt entier : environ 574 entrées et 620 000 tokens embarqués, à 86 à 165 tokens/s selon le CPU alloué par GitHub, soit 1 h 00 à 2 h 05 par run (mesures d'octobre 2026, `docs/developer/semantic-search.md`). Le workflow se déclenchait sur les PR qui touchent la recherche sémantique, avec `cancel-in-progress`, sur `workflow_dispatch` et sur un `schedule` mensuel qui s'exécute sur la branche par défaut `main`. Aucun run n'avait eu lieu sur `develop` depuis l'audit : une PR poussée plusieurs fois annule ses runs avant leur terme, et rien ne qualifie le code une fois fusionné. `release.yml` ne dépend pas de cette qualification.

La question est double : sur quel corpus et à quel moment la qualification réelle s'exécute-t-elle pour produire une mesure qui termine, et une release doit-elle l'exiger ?

## Options envisagées

- **Option A — corpus complet partout, exigé en release** : une seule mesure, la plus forte. Chaque PR concernée et chaque release attendent jusqu'à 2 h 05 sur un runner dont le débit varie du simple au double ; un run lent bloque la release sans rien dire du code.
- **Option B — corpus réduit sur PR, corpus complet sur `develop`, release non bloquée** : une PR reçoit un signal en moins de 45 minutes, le code fusionné est mesuré sur le corpus complet sans annulation, la release ne dépend pas d'une mesure de qualité d'une fonctionnalité désactivée par défaut.
- **Option C — corpus réduit partout** : rapide, mais moins de distracteurs ; une régression qui n'apparaît qu'à l'échelle du dépôt n'est jamais vue.

## Decision

**Option retenue : Option B.**

- **Pull request** : le job exécute le corpus `pr`, un sous-ensemble déclaré dans `RealSemanticBenchmarkCorpus`, utilisé par `RealSemanticSearchBenchmarkTest` (`docs/developer/`, ADR `BE-*` et `DATA-*`, classes de premier niveau des paquets `com.nexus.context` et `com.nexus.search` de `core`, paquet `com.nexus.context.source.git`, sources de `adapters/mcp-java`). Il contient tous les documents jugés pertinents par les requêtes du benchmark et environ 173 000 tokens embarqués, contre 620 000 pour le corpus complet. Sa borne est `timeout-minutes: 45`. Les seuils sont ceux du corpus complet.
- **`develop`** : un `push` sur `develop` qui touche les chemins surveillés exécute le corpus `full`, borné à 180 minutes. Le groupe de concurrence d'un run hors PR est le SHA qualifié : un push ultérieur sur `develop` ne l'annule pas, et un run en cours n'est jamais annulé ; seul un run en attente sur le même SHA peut être remplacé. Le rapport porte `snapshotCommit` égal à ce SHA.
- **Release** : `release.yml` n'exige pas la qualification sémantique réelle. La recherche sémantique est désactivée par défaut (BE-0014) ; la preuve de qualité est le dernier run `full` réussi sur `develop`, que le mainteneur consulte avant une promotion (`gh run list -w semantic-search-qualification.yml --branch develop`).
- `workflow_dispatch` choisit le corpus (`full` par défaut) ; le `schedule` mensuel exécute `full` sur `main`.

Cette décision ne change ni les seuils, ni le modèle, ni le runtime Ollama.

## Do's and Don'ts

### Do

- Exécuter le corpus `pr` sur pull request avec `timeout-minutes` au plus égal à 45.
- Exécuter le corpus `full` sur `develop` après la fusion d'un changement des chemins surveillés, sans annulation par un run ultérieur.
- Appliquer les mêmes seuils aux deux corpus.
- Garder dans le corpus `pr` chaque chemin jugé pertinent par une requête du benchmark ; le test échoue sinon.

### Don't

- Ne pas ajouter la qualification sémantique réelle aux `needs` du job `publish` de `release.yml` sans un ADR qui remplace celui-ci.
- Ne pas abaisser un seuil pour faire passer le corpus `pr`.
- Ne pas annuler un run en cours hors pull request (`cancel-in-progress` limité aux pull requests).

## Consequences

### Positives

- Une PR qui touche la recherche sémantique obtient un verdict en moins de 45 minutes, même sur le runner le plus lent observé.
- Le code fusionné dans `develop` est qualifié sur le corpus complet, et ce run va à son terme.
- La release ne dépend pas du débit CPU d'un runner pour une fonctionnalité optionnelle.

### Négatives

- Le corpus `pr` a moins de distracteurs : une régression qui ne se manifeste qu'à l'échelle du dépôt n'est détectée qu'après fusion, sur `develop`.
- Une release peut partir alors que le dernier run `full` de `develop` a échoué ; c'est au mainteneur de le vérifier avant la promotion.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Régression masquée par le corpus réduit | Moyen | Run `full` sur `develop` après fusion, mêmes seuils ; un échec bloque la promotion suivante par décision du mainteneur |
| Corpus `pr` qui dépasse 45 min avec la croissance du dépôt | Faible | `throughput.json` mesure tokens et débit à chaque run ; le corpus est ajusté avant d'augmenter la borne |
| Chemin pertinent sorti du corpus `pr` | Faible | Le test vérifie la présence de chaque chemin pertinent avant d'indexer |

## Compliance and Enforcement

- Contrat `scripts/release/test-final-audit-contracts.sh` : déclencheurs `pull_request` et `push` sur `develop`, borne de 45 minutes pour le corpus `pr` et de 180 minutes pour le corpus `full`, sélection du corpus, absence d'annulation hors PR, absence de la qualification dans les `needs` de `publish`, budgets documentés dans `docs/developer/ci-and-supply-chain.md`.
- `RealSemanticBenchmarkCorpus` (utilisé par `RealSemanticSearchBenchmarkTest`) refuse un corpus inconnu et un corpus `pr` qui ne contient pas un chemin pertinent ; `RealSemanticBenchmarkCorpusTest` vérifie à chaque build que le corpus `pr` du dépôt contient chaque chemin pertinent.
- Revue : un changement des requêtes du benchmark garde leurs chemins pertinents dans le corpus `pr`.

## Conditions de réexamen

La décision est réévaluée si la recherche sémantique devient active par défaut, si le corpus `full` termine de façon fiable sous 45 minutes, ou si un run `full` sur `develop` révèle une régression que le corpus `pr` n'a pas vue.

## Mise en œuvre

Changement OpenSpec `sprint-02-outillage-scripts-workflows`, tâches 3.1 à 3.3.

## References

- [BE-0014](BE-0014-rendre-la-recherche-semantique-optionnelle.md) — recherche sémantique optionnelle.
- `docs/developer/semantic-search.md` — mesures de débit et budget de temps.
- `docs/developer/ci-and-supply-chain.md` — qualification sémantique réelle.
- `docs/project/audits/2026-10-17/findings.md` — constat `AUD-DEP-19`.
