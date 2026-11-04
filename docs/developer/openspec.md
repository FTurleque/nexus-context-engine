# OpenSpec : produire, maintenir et contrôler les spécifications

Ce guide décrit le schéma de projet `detailed-uml` (`openspec/schemas/detailed-uml/`), sélectionné par `openspec/config.yaml`. Il complète les règles de [documentation](../../.claude/rules/documentation.md) et ne les remplace pas.

## Répartition des rôles

| Support | Rôle | Emplacement |
|---|---|---|
| Delta de changement | exigences ajoutées, modifiées, retirées ; proposition, conception et tâches du changement | `openspec/changes/<nom>/` |
| Spec de référence | comportement exigé, consolidé à l'archivage | `openspec/specs/<capacité>/spec.md` |
| Page durable d'une capacité | existant vérifié, vues Mermaid, écarts, propositions | section `## OpenSpec <capacité>` d'une page de `docs/developer/` ou de `docs/architecture/` |
| Documentation d'architecture | vision consolidée et diagrammes maintenus | [arc42](../architecture/README.md), [overview](../architecture/overview.md) |
| ADR | décision structurante, transversale, coûteuse à inverser | [registre](../architecture/adr/README.md) (skill `nexus-adr`) |
| Matrice de traçabilité | exigence → scénario → vue → tâche → preuve | [openspec-traceability.md](openspec-traceability.md) |

La synchronisation des specs ne consolide que les blocs `### Requirement:` : une section d'un delta hors `## Purpose` et opérations n'est pas reportée (`openspec validate` le signale). Les diagrammes ne vont donc jamais dans un delta : ils vont dans la page durable, mise à jour par le groupe de tâches « Documentation durable ».

## Nature de l'information

Chaque affirmation de proposition, de conception et de page durable porte une étiquette :

| Étiquette | Sens |
|---|---|
| `[Exigence]` | comportement attendu, énoncé par une exigence SHALL/MUST |
| `[Existant vérifié]` | constat relu dans le code, un test ou un document, avec sa source `chemin:ligne` |
| `[Décision]` | choix de conception tranché |
| `[Proposition]` | évolution à valider, jamais présentée comme disponible |
| `[Question]` | point ouvert |
| `[Écart]` | divergence entre code, tests et specs ; l'exigence n'est pas réécrite pour s'y conformer |

Le code existant prouve l'implémentation, pas l'intention métier.

## Produire la prochaine spécification

```bash
openspec new change <nom-kebab-case>
```

Le schéma vient de `config.yaml` et s'inscrit dans `openspec/changes/<nom>/.openspec.yaml`. Chaîne des artefacts : `proposal` → `specs` → `design` (dépend de `proposal` et `specs`) → `tasks` (dépend de `specs` et `design`).

```bash
openspec status --change <nom>
openspec instructions proposal --change <nom>
openspec instructions specs --change <nom>
openspec instructions design --change <nom>
openspec instructions tasks --change <nom>
openspec validate <nom> --strict
```

Les skills `/opsx:propose`, `/opsx:apply`, `/opsx:sync` et `/opsx:archive` appellent ces mêmes instructions. Ne lancer ni la synchronisation ni l'archivage d'un comportement seulement proposé, ni sans demande explicite.

Un changement purement documentaire ou d'outillage pose `skip_specs: true` dans son `.openspec.yaml`, déclare son type `documentaire` ou `outillage` et garde un `design.md` au profil `court` (la tâche `tasks` en dépend toujours).

## Conventions des specs

- Un titre d'exigence est sa clé de synchronisation ; il ne change que par un delta `RENAMED`. Aucun identifiant n'est ajouté aux titres : la référence stable d'une exigence est `capacité › titre`, d'un scénario `capacité › titre › nom`.
- Scénarios : `GIVEN` (omis si trivial), `WHEN`, `THEN`, `AND` (effets persistés ou leur absence).
- Dernière ligne du `## Purpose` : `Documentation durable : docs/<page>.md#openspec-<capacité>`.
- Une description d'exigence tient en 500 caractères ou moins (`--strict`).

## Contrôles

| Contrôle | Commande | Ce qu'il prouve |
|---|---|---|
| Validation structurelle OpenSpec | `openspec validate --all --strict` | format des specs et des changements |
| Validation du schéma | `openspec schema validate detailed-uml` | YAML, dépendances, modèles |
| Schéma résolu et instructions | `openspec schema which detailed-uml`, `openspec instructions <artefact> --change <nom>` | origine du schéma, `context` et `rules` effectivement servis |
| Contrats documentaires OpenSpec (CI) | `bash .claude/scripts/contracts.sh` | schéma, `.openspec.yaml`, Purpose, matrice complète, chemins de preuve, liens, types Mermaid |
| Syntaxe Mermaid | voir ci-dessous | chaque diagramme est parsé par Mermaid |
| Revue sémantique | relecture humaine | complétude des règles métier ; aucune commande ne la prouve |

Un contrôle qui réussit ne prouve pas que les règles métier soient complètes.

Syntaxe Mermaid (hors CI : elle installe des paquets npm, à épingler) :

```bash
mkdir -p "$TMPDIR/mmd" && cp scripts/openspec/check-mermaid.mjs "$TMPDIR/mmd/"
(cd "$TMPDIR/mmd" && echo '{"type":"module","private":true}' > package.json && npm i mermaid@11.4.1 jsdom@25.0.1 --no-audit --no-fund)
node "$TMPDIR/mmd/check-mermaid.mjs" docs/developer/openspec-traceability.md docs/developer/*.md docs/architecture/arc42/*.md
```

## Maintenir le schéma et le comparer au schéma officiel

`detailed-uml` est un instantané du schéma `spec-driven` d'OpenSpec 1.14.3 : il ne reçoit pas ses évolutions, et `openspec update` ne touche pas `openspec/schemas/`. Empreintes SHA-256 de la base :

| Fichier du schéma officiel | SHA-256 |
|---|---|
| `schema.yaml` | `b6f28f305ab7f33b061949251bba82c79de66c82424cfed60582327db3f888f2` |
| `templates/design.md` | `6ccc87f5017fb190e69e8759dd4e20d5ac3320cc4dabf152708bf8f2aa5205f8` |
| `templates/proposal.md` | `0dd70e35db5c696ba8ac3cc3a53e2d6561865c1e4e7d49db5a1eaa3e4a6ad86e` |
| `templates/spec.md` | `1cccfb573f031c6352bed2a879b7035ff710bc909112fc23d10bdcfcdd8d4318` |
| `templates/tasks.md` | `fa67d99fb4a93b12d702f89ea83e1838219d1cad1c90a4192c402278001cd30e` |

Après une montée d'OpenSpec (`openspec --version`), comparer :

```bash
OFFICIAL="$(npm root -g)/@fission-ai/openspec/schemas/spec-driven"
(cd "$OFFICIAL" && sha256sum schema.yaml templates/*.md)
diff -ru "$OFFICIAL" openspec/schemas/detailed-uml
```

Une empreinte différente de la table signale un changement amont : relire le diff, reporter ce qui est utile dans `detailed-uml` (règles de format que `validate` impose, comme la limite de 500 caractères ou le `## Purpose`), mettre à jour la table, puis `openspec schema validate detailed-uml`. Toute modification du schéma est validée avant usage, car les erreurs de champ mal orthographié (`instrution`) sont ignorées silencieusement.

## Changements actifs et archives

Un changement garde le schéma de son `.openspec.yaml`. Les changements `sprint-01`, `sprint-06` et `sprint-07` n'en avaient pas : un `.openspec.yaml` `schema: spec-driven` les épingle, sans quoi ils auraient suivi `config.yaml`. Les artefacts des changements actifs peuvent être enrichis selon les conventions ci-dessus sans changer de schéma. Les archives (`openspec/changes/archive/`) restent figées.

## Limites

- Les commandes `openspec schema` sont expérimentales.
- La CI documentaire est statique : elle n'exécute pas `openspec validate` (la CLI n'est pas installée par les workflows) et ne parse pas les diagrammes.
- La matrice cite les tests lus à la rédaction ; elle n'établit pas qu'ils passent sur le HEAD courant (voir « Preuve » dans les [règles de documentation](../../.claude/rules/documentation.md)).
