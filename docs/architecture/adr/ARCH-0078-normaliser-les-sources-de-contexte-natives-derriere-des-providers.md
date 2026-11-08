---
id: ARCH-0078
title: "Découvrir et normaliser les sources de contexte natives des projets derrière des providers"
domain: architecture
rules: true
---

# Découvrir et normaliser les sources de contexte natives des projets derrière des providers

- **Statut** : Accepted
- **Date** : 2026-11-08
- **Origine** : consolidation du registre ADR (audit du 2026-11-08)
- **Remplace** : [ARCH-0011](archive/ARCH-0011-normaliser-les-sources-de-contexte.md), [ARCH-0012](archive/ARCH-0012-reutiliser-les-standards-instructions-et-agent-skills.md), [BE-0032](archive/BE-0032-preserver-et-normaliser-le-contexte-natif-des-projets.md)

## Context

NEXUS sélectionne des informations de formes et d'origines différentes : code, symboles, tests, documentation, instructions, skills, profils d'agents, prompts et contexte Git. Beaucoup de repositories portent déjà une configuration destinée aux assistants, hétérogène et parfois imbriquée :

```text
AGENTS.md / AGENT.md
.github/copilot-instructions.md
.github/instructions/**/*.instructions.md
CLAUDE.md / .claude/CLAUDE.md
GEMINI.md
.github/skills/**/SKILL.md, .claude/skills/**/SKILL.md, .agents/skills/**/SKILL.md
```

Dans un monorepository, plusieurs `AGENTS.md` ou `CLAUDE.md` coexistent à différents niveaux ; les `*.instructions.md` Copilot déclarent un scope `applyTo` ; certains fichiers référencent d'autres documents par `@chemin`.

Si le cœur traitait lui-même chaque convention fournisseur, il se remplirait de conditions propres à Copilot, Claude ou Gemini, perdrait son indépendance ([ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md)) et rendrait le classement difficile à généraliser. Inventer un format NEXUS obligerait les utilisateurs à dupliquer leur configuration ; concaténer tous les fichiers trouvés injecterait des instructions hors scope.

La question : **comment découvrir et combiner le contexte natif déjà configuré dans un projet, sans format propriétaire, sans perdre la sémantique de scope et en gardant un pipeline commun de recherche, de classement et de budget ?**

## Options envisagées

Architecture des sources :

- **Chaque type codé dans `ContextBuilder`** : rapide pour quelques sources, mais complexité croissante, couplage aux formats, tests difficiles.
- **Un pipeline par fournisseur** : adaptation maximale, mais recherche, classement et budget dupliqués, résultats différents selon le client.
- **Providers et modèle commun** : extensible, responsabilités claires, pipeline commun ; modèle commun plus exigeant à concevoir, un mapping par format.
- **Tout convertir en texte brut** : modèle trivial, mais perte du scope, de la provenance, du type et de la priorité.

Formats :

- **Formats propriétaires `.nexus-instructions` / `.nexus-skill`** : schéma maîtrisé, mais duplication pour les utilisateurs et standard supplémentaire à maintenir.
- **Formats Copilot seuls** : périmètre réduit, mais couplage fournisseur.
- **`AGENTS.md` et Agent Skills seuls** : standards portables, mais migration imposée aux projets qui utilisent d'autres conventions.
- **Plusieurs conventions via des providers, `AGENTS.md` et Agent Skills privilégiés** : interopérable, sans migration ; davantage de parseurs et une résolution de conflits plus complexe.

Combinaison du contexte natif : ignorer les configurations natives, tout concaténer, déléguer la résolution au client final, ou **normaliser en conservant scope et priorité**.

## Decision

**Options retenues : providers et modèle commun ; plusieurs conventions natives prises en charge, `AGENTS.md` privilégié pour les instructions portables et le standard Agent Skills (`SKILL.md`) adopté pour les skills ; applicabilité résolue à partir des fichiers candidats de la requête.**

### Providers et pipeline commun

Les types de contexte conceptuels sont `CODE`, `SYMBOL`, `TEST`, `DOCUMENTATION`, `INSTRUCTION`, `SKILL`, `AGENT_PROFILE`, `PROMPT` et `GIT`. Chaque famille de sources natives est découverte par un provider. Les providers comprennent les conventions natives ; le cœur normalise, recherche, classe et budgète. Des stratégies ou poids propres à un type restent possibles, mais convergent vers un pipeline commun. Une nouvelle source s'ajoute sans modifier les contrats des autres providers, et un même `ContextBundle` combine plusieurs types de contexte.

Le modèle normalisé conserve au minimum : `id`, `provider`, `origin`, `path`, `type`, `scope`, `applyTo`, `priority`, `content`, `metadata`, `reasons`. Chaque candidat garde son origine et sa stratégie de découverte.

`ContextBuilder` et le pipeline commun ne lisent pas `.github/`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` ou `SKILL.md` et ne contiennent aucune condition propre à Copilot, Claude ou Gemini.

### Conventions d'instructions

NEXUS ne remplace pas les fichiers natifs et n'exige pas leur migration ; un projet qui n'a que des fichiers natifs s'utilise sans créer de fichier NEXUS.

- `.github/copilot-instructions.md` : instruction de repository.
- `.github/instructions/**/*.instructions.md` : le frontmatter `applyTo` est comparé aux chemins cibles. Un fichier sans `applyTo` valide n'est pas global implicitement : il est signalé comme non applicable.
- `AGENTS.md` : présent à plusieurs niveaux ; son scope est l'arborescence sous son répertoire parent. Les instructions plus proches du fichier cible ont une priorité supérieure ; les parentes restent disponibles. `AGENT.md` est un alias de compatibilité, non le format portable privilégié.
- `CLAUDE.md` dans l'arborescence : scope répertoire ; `.claude/CLAUDE.md` à la racine : instruction de repository.
- `GEMINI.md` : scope répertoire, selon sa position.

### Résolution du scope et priorité

Le contexte de résolution part des meilleurs candidats de `SearchService` : requête → `SearchService` → fichiers et symboles candidats → chemins cibles → providers → instructions applicables uniquement.

- Une instruction repository-wide s'applique toujours.
- Une instruction de scope répertoire s'applique si au moins un chemin cible appartient à son sous-arbre.
- Une instruction `applyTo` s'applique si au moins un chemin cible correspond à l'un de ses motifs.

La priorité n'est pas un remplacement destructif : elle sert au classement et à l'arbitrage sous budget. Ordre de spécificité : `applyTo` > instruction imbriquée proche du fichier cible > instruction repository-wide > alias ou convention générique. La plus spécifique est favorisée quand le budget ne permet pas de tout inclure.

### Références `@chemin`

Pour les formats qui les acceptent, NEXUS peut charger les fichiers référencés, sous ces contraintes : seules les références résolues dans le repository sont chargées ; chemins absolus et références vers le home utilisateur refusés ; récursion limitée à cinq niveaux ; cycles détectés ; références situées dans un bloc de code Markdown non interprétées. Une référence hors repository est ignorée et expliquée. Cette politique privilégie reproductibilité et sécurité même quand l'outil natif autorise davantage.

### Déduplication

Deux instructions au contenu normalisé identique ne consomment pas deux fois le budget : NEXUS garde la source de plus forte priorité et explique la déduplication.

### Skills

NEXUS adopte le standard Agent Skills et n'introduit aucun format de skill propriétaire obligatoire. Les skills suivent la divulgation progressive (métadonnées à la découverte, `SKILL.md` à la sélection, scripts, références et assets chargés par l'agent à l'exécution) ; NEXUS sélectionne ou recommande un skill et ne l'exécute pas. Le détail est fixé par [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md).

### Hors décision

La séparation entre instructions contextuelles et configuration opérationnelle des agents (hooks, permissions, serveurs MCP, settings) relève de [SEC-0033](SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md). Le contexte Git relève de [BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md).

## Do's and Don'ts

### Do

- Faire découvrir chaque famille de sources de contexte natives par un provider (`ContextSourceProvider` pour les instructions) et la normaliser dans le modèle commun (`id`, `provider`, `origin`, `path`, `type`, `scope`, `applyTo`, `priority`, `content`, `metadata`, `reasons`).
- Laisser aux providers la compréhension des conventions natives, et au cœur la normalisation, la recherche, le classement et le budget.
- Conserver pour chaque candidat son origine et sa stratégie de découverte.
- Découvrir `.github/copilot-instructions.md`, `.github/instructions/**/*.instructions.md`, `AGENTS.md`, `AGENT.md`, `CLAUDE.md`, `.claude/CLAUDE.md` et `GEMINI.md`, en privilégiant `AGENTS.md` comme format portable.
- Résoudre l'applicabilité à partir des chemins cibles tirés des meilleurs candidats de `SearchService`.
- Appliquer toujours une instruction repository-wide ; une instruction de scope répertoire seulement si un chemin cible appartient à son sous-arbre ; une instruction `applyTo` seulement si un chemin cible correspond à l'un de ses motifs.
- Classer par spécificité (`applyTo` > instruction imbriquée proche du fichier cible > instruction repository-wide > alias ou convention générique) et favoriser la plus spécifique sous budget.
- Traiter `AGENT.md` comme un alias de compatibilité, `.claude/CLAUDE.md` racine comme instruction de repository, `CLAUDE.md` et `GEMINI.md` comme instructions de scope répertoire.
- Ne compter qu'une fois deux instructions au contenu normalisé identique : garder la source de plus forte priorité et expliquer la déduplication.
- Adopter le standard Agent Skills (`SKILL.md`) en divulgation progressive.

### Don't

- Ne pas faire lire `.github/`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` ou `SKILL.md` directement par `ContextBuilder`, ni y placer de condition propre à Copilot, Claude ou Gemini.
- Ne pas modifier les contrats des autres providers pour ajouter une nouvelle source.
- Ne pas remplacer les fichiers natifs ni exiger leur migration ; ne pas introduire de format d'instruction ou de skill propriétaire obligatoire.
- Ne pas traiter comme globale une instruction Copilot sans `applyTo` valide : la signaler comme non applicable.
- Ne pas utiliser la priorité comme un remplacement destructif des instructions parentes.
- Ne pas charger une référence `@chemin` résolue hors du repository, absolue ou vers le home utilisateur.
- Ne pas suivre les références `@` au-delà de cinq niveaux ni à travers un cycle, ni interpréter celles d'un bloc de code Markdown.
- Ne pas implémenter l'exécution d'un skill dans le cœur du `ContextBuilder`.

## Consequences

### Positives

- Les projets conservent leurs fichiers natifs, scopes locaux de monorepository compris ; aucune migration.
- Une nouvelle source s'ajoute sans réécrire le moteur ; `ContextBuilder` reste indépendant de Copilot ou Claude.
- Provenance et scope sont explicites ; les exclusions inter-sources s'expliquent uniformément.
- Les instructions Copilot ciblées ne polluent pas les requêtes non concernées ; les adaptateurs exposent les mêmes instructions normalisées.

### Négatives

- Les conventions natives n'ont pas toutes la même sémantique : le modèle normalisé est une approximation contrôlée de certains comportements clients.
- Le modèle commun doit être assez riche sans devenir inutilement abstrait ; certaines sources demandent des métadonnées propres.
- Les conflits sémantiques entre instructions ne se résolvent pas automatiquement ; le classement entre types hétérogènes demande des règles de normalisation.
- Chaque nouveau format demande un provider ; certaines conventions propriétaires évoluent.

### Risques

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Fuite de concepts fournisseur dans le cœur | Élevé | Conventions natives limitées aux providers ; règles Archgate de ce registre |
| Injection d'une instruction hors scope | Élevé | Résolution explicite sur les chemins candidats |
| Instruction spécifique perdue sous budget | Élevé | Priorité croissante avec la spécificité |
| Lecture de fichiers hors repository | Élevé | Refus des références externes |
| Modèle commun trop générique | Élevé | Noyau commun et métadonnées typées ou extensions contrôlées |
| Comparaison incorrecte de scores entre sources | Élevé | Scores normalisés, sous-budgets et poids par type |
| Duplication entre Copilot, Claude et AGENTS | Moyen | Déduplication par contenu normalisé |
| Boucle de références `@` | Moyen | Profondeur maximale et détection de cycles |

## Compliance and Enforcement

- Règle Archgate `ARCH-0078-normaliser-les-sources-de-contexte-natives-derriere-des-providers.rules.ts` / `context-builder-reads-no-native-format` : les sources du package `com.nexus.context` lui-même (hors providers `com.nexus.context.source.*`) ne nomment ni `.github`, ni `AGENTS.md`, `AGENT.md`, `CLAUDE.md`, `GEMINI.md`, `SKILL.md`, `*.instructions.md` ou `copilot-instructions` ; commentaires effacés, littéraux conservés.
- Même règle / `context-builder-has-no-vendor-condition` : les sources du paquetage `com.nexus.context` hors sous-paquetages ne mentionnent ni Copilot, ni Claude, ni Gemini, ni `AGENTS.md` / `AGENT.md` ; commentaires effacés, littéraux conservés.
- Tests (`core`, lancés par `./mvnw -B clean install`, job « Linux reactor Maven build » de `.github/workflows/ci.yml`) : `NativeContextSourcesIntegrationTest#combinesApplicableNativeInstructionsCodeAndDocumentationWithoutInjectingOperationalConfig` (un même bundle combine instructions natives, code et documentation), `InstructionReferenceResolverTest` (références gardées dans le repository, cycles arrêtés, refus des liens symboliques sortants), `InstructionDiscoverySupportTest`.
- Revue : toute nouvelle convention native passe par un provider de `com.nexus.context.source.instruction` et respecte les contraintes des références `@`.

## Conditions de réexamen

La décision est réévaluée si plusieurs providers doivent contourner systématiquement le contrat commun ; si de nouvelles sources imposent des notions absentes (durée de validité, sécurité) ; si le classement multi-source ne s'explique plus avec les métadonnées existantes ; si un standard d'instructions universel s'impose ; si le standard Agent Skills évolue de manière incompatible ; si les providers fournisseurs deviennent trop coûteux à maintenir.

## Mise en œuvre

- Port et modèle : `core/src/main/java/com/nexus/context/source/ContextSourceProvider.java`, `ContextSourceDescriptor.java` (priorité 0 à 100), `ContextSourceScope.java` (`REPOSITORY`, `DIRECTORY_TREE`, `PATH_GLOB`) ; types concrets : `core/src/main/java/com/nexus/search/candidate/CandidateType.java` (`FILE`, `SYMBOL`, `TEST`, `DOCUMENTATION`, `INSTRUCTION`, `SKILL`, `GIT`).
- Providers d'instructions : `core/src/main/java/com/nexus/context/source/instruction/` (`AgentsMdInstructionProvider`, `CopilotInstructionProvider`, `ClaudeInstructionProvider`, `GeminiInstructionProvider`) ; références `@` : `InstructionReferenceResolver` (`MAX_DEPTH = 5`).
- Découverte et déduplication par empreinte du contenu normalisé : `ContextSourceDiscoveryService` ; pipeline : `core/src/main/java/com/nexus/context/NativeContextPipeline.java`.
- Ports des autres familles : `SkillSourceProvider` ([BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md)), `GitContextSourceProvider` ([BE-0070](BE-0070-integrer-le-contexte-git-local-borne-mis-en-cache-et-confine.md)) ; code, symboles et documentation viennent de l'index et de la recherche.
- Documentation : [native-context-sources.md](../../developer/native-context-sources.md), [native-context-discovery-limits.md](../../developer/native-context-discovery-limits.md).

## References

- [ARCH-0077](ARCH-0077-positionner-nexus-comme-moteur-de-contexte-independant.md) — moteur de contexte indépendant.
- [BE-0072](BE-0072-construire-le-contextbundle-sous-budget-de-tokens.md) — `ContextBundle` sous budget de tokens.
- [BE-0079](BE-0079-charger-les-agent-skills-par-divulgation-progressive.md) — Agent Skills par divulgation progressive.
- [SEC-0033](SEC-0033-separer-instructions-contextuelles-et-configuration-operationnelle-des-agents.md) — instructions contextuelles et configuration opérationnelle.
- AGENTS.md : https://agents.md/
- GitHub Copilot custom instructions : https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/add-custom-instructions/add-repository-instructions
- Claude Code memory : https://docs.anthropic.com/en/docs/claude-code/memory
