# Bilan de couverture OpenSpec : enrichissement des specs de référence (2026-11-04)

Relevé figé. Il décrit l'état du dépôt au commit `9ef9071f` (branche `docs/openspec-detailed-uml`, modifications non commitées) après l'introduction du schéma `detailed-uml`. Il ne vaut pas preuve pour un autre HEAD. Mode d'emploi du schéma : [OpenSpec](../../developer/openspec.md) ; matrice vivante : [Traçabilité OpenSpec](../../developer/openspec-traceability.md).

## Méthode et limites

- Chaque capacité a été relue contre le code, les tests et les workflows par lecture et recherche : aucun build Maven, aucun test, aucun workflow n'a été exécuté. Un statut `couvert` signifie qu'un test ou un contrat lu vérifie le scénario, pas qu'il passe.
- Les exigences, leurs titres et leurs descriptions n'ont pas été modifiés : contrôle automatisé contre `HEAD` (0 écart de conservation). Seules des lignes `Documentation durable` (Purpose), la structure `GIVEN/WHEN/THEN/AND` des scénarios et 13 scénarios illustrant des exigences existantes ont été ajoutés.
- Les écarts sont documentés, jamais corrigés dans la spec ni dans le code.
- Contrôles exécutés : `openspec validate --all --strict` (20 éléments valides), `openspec schema validate detailed-uml`, contrat statique `scripts/release/test-openspec-doc-contracts.sh`, syntaxe de 31 diagrammes Mermaid (aucun invalide). Non exécutés : `scripts/self-smoke.ps1`, Maven, CI GitHub.

## Specs de référence (11 / 11 traitées)

| Capacité | Scénarios | Couverts | Partiels | CI seulement | Non couverts |
|---|---|---|---|---|---|
| `adapter-contract-tests` | 7 | 4 | 2 | 0 | 1 |
| `ci-workflow-tooling` | 14 | 7 | 7 | 0 | 0 |
| `core-package-boundaries` | 12 | 9 | 1 | 1 | 1 |
| `git-recency-diagnostics` | 4 | 2 | 2 | 0 | 0 |
| `powershell-script-tooling` | 8 | 1 | 6 | 0 | 1 |
| `project-indexing-exclusions` | 6 | 5 | 1 | 0 | 0 |
| `project-path-confinement` | 10 | 9 | 1 | 0 | 0 |
| `public-output-projection` | 9 | 3 | 4 | 0 | 2 |
| `secret-redaction` | 10 | 7 | 2 | 0 | 1 |
| `semantic-search-qualification` | 4 | 1 | 2 | 1 | 0 |
| `test-suite-reliability` | 5 | 3 | 1 | 1 | 0 |
| **Total** | **89** | **51** | **29** | **3** | **6** |

Scénarios `non couverts` (test non écrit ou preuve manquante) : `adapter-contract-tests › Les tests de contrat ne dépendent pas de leur ordre › Ordre inversé` ; `core-package-boundaries › La politique de sortie ne dépend pas du package security › Contrôle des imports` ; `powershell-script-tooling › Aucune référence vers un artefact inexistant` ; `public-output-projection › Deux processus` ; `public-output-projection › Ajout d'un champ côté cœur` ; `secret-redaction › Index lexical d'une ancienne politique`. Le détail exact est dans la matrice.

## Changements actifs (9)

| Changement | Traité | Reste à traiter |
|---|---|---|
| `sprint-01` à `sprint-11` actifs (8) | `design.md` : section « Traçabilité » (une ligne par scénario, preuves « à produire »), 8 vues Mermaid marquées `[Proposition]`, écarts relevés ; schéma épinglé dans `.openspec.yaml` | `proposal.md`, `specs/` et `tasks.md` inchangés : scénarios sans `GIVEN`/`AND`, étiquettes de nature absentes ; chevauchements inter-sprints à arbitrer ; `proposal.md` de sprint-07 périmé |
| `preciser-bornes-redaction-recence-git` (nouveau, schéma `detailed-uml`) | proposition, 2 deltas, design, tâches ; validé en strict | 5 tâches non réalisées, 3 questions ouvertes ; ne pas synchroniser ni archiver avant réponse |

## Écarts relevés (code, tests, specs)

Les sources `chemin:ligne` sont dans la section « Écarts et limites » de chaque page durable ; l'auteur du relevé a revérifié par sondage la version de politique `secret-redaction-v6`, le seuil de 4 096, l'existence de `CoreArchitectureTest` et de `package-cycles.txt`, la position de `PublicContextPolicy` dans `exposure`.

- `secret-redaction` : seuils plus étroits que « 4 096 » (1 024 pour la partie utilisateur d'URL, 255 pour le corps de jetons à préfixe) ; le fichier trop long reste dans l'index structurel ; « une seule fois » avant embedding n'est vrai qu'à un point du chemin, la redaction rejouée ailleurs ; le profil `content-vN` n'est pas imposé par un test.
- `git-recency-diagnostics` : la spec ignore les budgets de chemins (512 / 1 024 / 4 096) et le niveau `DEBUG` du cas hors Git ; les bornes de commit n'ont pas de test.
- `project-indexing-exclusions` : la casse est ignorée sans que la spec l'exige ; la même règle gouverne la découverte native d'instructions et de skills.
- `project-path-confinement` : « refuse un chemin absolu » ne vaut que pour `resolve(Path)` ; la branche « chemin réel hors racine » n'est atteinte par aucun test lu ; la qualification filesystem ne lance pas `ProjectPathGuardTest`.
- `public-output-projection` : aucun test ne compare les trois canaux entre eux ; aucun test à deux JVM ; `rest-api.md` omet `/explain/search` et `/explain/context` ; `ARCH-0061` et `ARCH-0059` sont `Proposed` alors que le code est livré.
- `core-package-boundaries` : baseline de 22 arêtes en deux composantes (4 et 7 packages) ; le test est maison (`JavaSourceGraph`), un nom `com.nexus.*` pleinement qualifié lui échappe.
- `adapter-contract-tests` : `GET /api/v1/projects/{projectId}` n'a pas de test dédié ; la spec dit « exposé » pour `/q/health/ready` et `/q/metrics`, le test applicatif attend `404` (exposition réelle testée ailleurs) ; `IndexRepository` n'a plus de méthode `default`.
- `ci-workflow-tooling`, `semantic-search-qualification` : `real-ollama` tourne sur `ubuntu-latest` hors de la liste des runners épinglés ; commentaire de version accepté sous `vN`, `vN.N`, `vN.N.N` ; durées du corpus `pr` divergentes entre ADR (37 min) et page (35 min) ; `GEN-0060` encore `Proposed`.
- `powershell-script-tooling` : le code de sortie de Maven est dans les données de l'exception et aucun script ne le lit ; `0.1.0-SNAPSHOT` n'est cherché par aucun contrat.
- Changements actifs : `sprint-07` dit `openspec/specs/` vide, aucun test d'architecture, `PublicContextPolicy` encore dans `security` ; trois affirmations périmées au HEAD. `sprint-10` : `SECURITY.md` existe déjà. `sprint-11` : appelant de `ProjectGraphBuilder` inexact. `sprint-08` : visibilité de `ProjectScanLimits.from` inexacte.

## Décisions métier encore nécessaires

1. Les budgets de récence et les bornes étroites de redaction sont-ils contractuels ? (changement `preciser-bornes-redaction-recence-git`)
2. Faut-il aligner la spec « Une valeur sensible trop longue n'exclut que son fichier » sur le code (index structurel conservé), ou le code sur la spec ?
3. Le refus d'un chemin absolu vise-t-il toutes les méthodes de `ProjectPathGuard` ? Quel comportement public face à un chemin hors racine (REST renvoie aujourd'hui une erreur interne `internal_state_error`, par lecture) ?
4. Les jonctions Windows et points de montage sont-ils arrêtés par le contrôle du chemin réel ? (hypothèse non vérifiée)
5. `Symbol.name` doit-il être redigé en sortie publique ? La spec ne liste que quatre champs.
6. `real-ollama` relève-t-il de la « qualification de performance » (runner épinglé) ? Le `corpus=pr` en `workflow_dispatch` avec 180 min de timeout est-il voulu ?
7. Faut-il propager le code de sortie Maven, ou reformuler le scénario ?
8. Chevauchements entre sprints 9, 10 et 11 (`operational-documentation-contracts`, `repository-security-settings`, scripts `build-windows-*.ps1`) et entre sprints 5 et 8 (`ProjectIndexingService`) : à arbitrer avant exécution.

## Propositions de tests (aucune réalisée)

Parité unique des trois canaux sur `path`, `id`, `excerpt`, `scoreComponents` ; test à deux JVM ; garde interdisant un record d'opération dans le module REST ; test de `GET /projects/{id}` ; exécution des classes de contrat en ordre inversé ; garde sur `IndexRepository` (aucune méthode `default`) ; contrôle exigeant un lanceur pour tout test opt-in ; test avec seule `GITHUB_ACTIONS` ; contrat sur `0.1.0-SNAPSHOT` ; test de `Invoke-NexusJson` ; épinglage des seuils sémantiques ; règle « `security` sans `context` » et détection des noms qualifiés dans le test d'architecture.

## Reste à traiter

- Convertir en deltas OpenSpec les propositions que les décisions ci-dessus valideront (un changement `detailed-uml` par sujet).
- Enrichir `proposal.md`, `specs/` et `tasks.md` des 8 changements `sprint-*` lorsqu'ils seront repris pour exécution, et réexaminer leurs affirmations périmées.
- Exécuter `scripts/self-smoke.ps1`, Maven et la CI sur le commit qui portera ces changements ; relire les statuts `couvert` contre un run exact-head.
- Les specs créées par les futurs changements doivent porter leur ligne `Documentation durable` et leurs lignes de matrice (le contrat l'impose).
