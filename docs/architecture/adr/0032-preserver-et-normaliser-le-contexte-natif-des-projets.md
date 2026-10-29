---
status: accepted
date: 2026-07-19
---

# ADR-0032 — Préserver et normaliser le contexte natif des projets

## Contexte et problème

De nombreux repositories possèdent déjà une configuration de contexte ou d'instructions destinée aux assistants et agents IA. Les conventions sont hétérogènes et parfois imbriquées :

```text
AGENTS.md
AGENT.md
.github/copilot-instructions.md
.github/instructions/**/*.instructions.md
CLAUDE.md
.claude/CLAUDE.md
GEMINI.md
```

Dans les monorepositories, plusieurs fichiers `AGENTS.md` ou `CLAUDE.md` peuvent coexister à différents niveaux. Les fichiers Copilot `*.instructions.md` peuvent déclarer un scope `applyTo`. Certains fichiers d'instructions peuvent également référencer d'autres documents avec une syntaxe `@chemin`.

NEXUS doit tenir compte de cette configuration existante sans demander une migration vers un format propriétaire et sans perdre la sémantique native de scope.

La question est : **comment NEXUS doit-il découvrir et combiner le contexte natif déjà configuré dans une application ?**

## Facteurs de décision

- préserver les conventions existantes du repository ;
- ne pas imposer de duplication de configuration ;
- conserver la provenance et le scope ;
- respecter les instructions imbriquées des monorepositories ;
- éviter qu'une instruction non applicable soit injectée dans le contexte ;
- rester indépendant de Copilot, Claude ou Gemini dans le cœur ;
- permettre l'ajout futur de nouveaux providers ;
- garder un comportement déterministe et explicable.

## Options envisagées

- ignorer les configurations natives et demander un format NEXUS ;
- concaténer tous les fichiers d'instructions trouvés ;
- convertir les fichiers natifs en un modèle commun en conservant leur scope et leur priorité ;
- déléguer entièrement la résolution des instructions au client final.

## Décision retenue

**NEXUS découvre les conventions natives via des `ContextSourceProvider`, les normalise dans un modèle commun et résout leur applicabilité à partir des fichiers candidats de la requête.**

Le modèle normalisé conserve au minimum :

```text
id
provider
origin
path
type
scope
applyTo
priority
content
metadata
reasons
```

### Conventions prises en charge dans l'Itération 5

#### Instructions de repository

```text
.github/copilot-instructions.md
```

Elles sont considérées comme applicables au repository entier.

#### Instructions Copilot ciblées par chemin

```text
.github/instructions/**/*.instructions.md
```

Le frontmatter `applyTo` est interprété et comparé aux chemins des fichiers candidats de la requête.

Un fichier sans `applyTo` valide n'est pas considéré comme global implicitement : il est signalé comme non applicable afin d'éviter une injection trop large.

#### Instructions AGENTS

```text
AGENTS.md
```

Les fichiers peuvent être présents à plusieurs niveaux. Leur scope est l'arborescence située sous leur répertoire parent.

Les instructions plus proches du fichier cible reçoivent une priorité supérieure. Les instructions parentes restent disponibles afin de conserver les règles globales non contradictoires.

Pour compatibilité avec certains repositories existants, NEXUS peut également découvrir :

```text
AGENT.md
```

Ce nom est traité comme un alias de compatibilité et non comme le format portable privilégié.

#### Instructions Claude

```text
CLAUDE.md
.claude/CLAUDE.md
```

Un `CLAUDE.md` placé dans l'arborescence est traité comme une instruction de scope répertoire. `.claude/CLAUDE.md` à la racine est traité comme une instruction de repository.

#### Instructions Gemini

```text
GEMINI.md
```

Le fichier est normalisé comme une instruction de scope répertoire, selon sa position dans l'arborescence.

### Résolution du scope

Le contexte de résolution est construit à partir des meilleurs candidats renvoyés par `SearchService`.

```text
requête
  ↓
SearchService
  ↓
fichiers/symboles candidats
  ↓
chemins cibles
  ↓
ContextSourceProvider
  ↓
instructions applicables uniquement
```

Une instruction repository-wide s'applique toujours.

Une instruction de scope répertoire s'applique lorsqu'au moins un chemin cible appartient à son sous-arbre.

Une instruction `applyTo` s'applique lorsqu'au moins un chemin cible correspond à un de ses motifs.

### Priorité

La priorité n'est pas une règle de remplacement destructif. Elle sert au classement et à l'arbitrage sous budget.

Ordre de spécificité initial :

```text
path-specific applyTo
    > instruction imbriquée proche du fichier cible
    > instruction repository-wide
    > alias ou convention générique
```

Les instructions plus spécifiques sont favorisées lorsque le budget ne permet pas de tout inclure.

### Références vers d'autres fichiers

Pour les formats qui acceptent des références de type `@chemin`, NEXUS peut charger des fichiers référencés afin de réutiliser un contexte déjà factorisé dans le repository.

Contraintes :

- seules les références résolues à l'intérieur du repository sont autorisées ;
- les chemins absolus et les références vers le home utilisateur ne sont pas chargés ;
- les références récursives sont limitées à cinq niveaux ;
- les cycles sont détectés ;
- les références trouvées dans des blocs de code Markdown ne sont pas interprétées.

Cette politique privilégie reproductibilité et sécurité, même lorsqu'un outil natif autorise des références plus larges.

### Déduplication

Deux instructions dont le contenu normalisé est identique ne doivent pas consommer deux fois le budget.

NEXUS conserve la source de plus forte priorité et explique qu'une source équivalente a été dédupliquée.

## Conséquences positives

- les applications existantes conservent leurs configurations ;
- aucune migration vers un fichier NEXUS obligatoire ;
- les monorepositories peuvent conserver leurs scopes locaux ;
- les instructions Copilot ciblées ne polluent pas les requêtes non concernées ;
- le moteur reste indépendant du fournisseur ;
- les décisions de sélection restent explicables ;
- le futur adaptateur MCP ou REST pourra exposer les mêmes instructions normalisées.

## Conséquences négatives et compromis acceptés

- les conventions natives n'ont pas toutes exactement la même sémantique ;
- le modèle normalisé est une approximation contrôlée de certains comportements clients ;
- les conflits sémantiques entre deux instructions ne peuvent pas être résolus automatiquement de manière parfaite ;
- de nouveaux formats nécessiteront de nouveaux providers.

## Risques et mesures de maîtrise

| Risque | Impact | Mesure |
|---|---|---|
| Injection d'une instruction hors scope | Élevé | Résolution explicite sur les chemins candidats |
| Instruction spécifique perdue sous budget | Élevé | Priorité croissante avec la spécificité |
| Duplication entre Copilot, Claude et AGENTS | Moyen | Déduplication par contenu normalisé |
| Lecture de fichiers hors repository | Élevé | Refus des références externes |
| Boucle de références `@` | Moyen | Profondeur maximale et détection de cycles |
| Couplage du cœur à un fournisseur | Élevé | Providers séparés et modèle normalisé |

## Confirmation

La décision est respectée si :

- un projet contenant uniquement des fichiers natifs peut être utilisé sans créer de fichier NEXUS ;
- un `AGENTS.md` imbriqué n'est appliqué qu'aux fichiers de son sous-arbre ;
- un `*.instructions.md` Copilot respecte `applyTo` ;
- la provenance de chaque instruction sélectionnée est explicable ;
- une instruction plus spécifique est prioritaire sous budget ;
- une référence `@` hors repository est ignorée et expliquée ;
- `ContextBuilder` ne contient aucune condition spécifique à Copilot ou Claude.

## Références

- AGENTS.md : https://agents.md/
- GitHub Copilot custom instructions : https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/add-custom-instructions/add-repository-instructions
- GitHub Copilot CLI custom instructions : https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-custom-instructions
- Claude Code memory : https://docs.anthropic.com/en/docs/claude-code/memory

## Décisions liées

- ADR-0011 — Normaliser les sources de contexte derrière des providers.
- ADR-0012 — Réutiliser les standards existants pour les instructions et les skills.
- ADR-0013 — Construire un `ContextBundle` sous budget de tokens explicable.
- ADR-0017 — Découpler NEXUS des outils et orchestrateurs externes.
