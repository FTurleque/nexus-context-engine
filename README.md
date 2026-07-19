# NEXUS Context Engine

> Un moteur local, indépendant des modèles, dédié à l'intelligence de contexte pour les projets logiciels.

NEXUS construit un contexte minimal, pertinent, explicable et traçable pour les assistants et agents IA. NEXUS n'est ni un chatbot, ni un LLM, ni un routeur d'agents.

## Mission

À partir d'un repository logiciel local et d'une demande en langage naturel, NEXUS identifie et classe les informations utiles, puis construit un `ContextBundle` respectant un budget de tokens configurable.

```text
Utilisateur / Agent / IDE
          │
          ▼
        Demande
          │
          ▼
        NEXUS
   ├── code / symboles / tests
   ├── documentation pertinente
   ├── instructions natives applicables
   ├── ranking explicable
   └── budget de contexte
          │
          ▼
     ContextBundle
          │
          ▼
      LLM / Agent IA
```

## Principe directeur

> **Respecter le contexte natif du projet avant d'ajouter du contexte NEXUS.**

Un projet qui possède déjà `AGENTS.md`, `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md`, `CLAUDE.md`, `.claude/CLAUDE.md` ou `GEMINI.md` n'a pas besoin de migrer vers un format propriétaire.

NEXUS découvre ces conventions via des providers, résout leur scope et les normalise dans un modèle commun.

À l'inverse, les configurations opérationnelles telles que `.claude/settings.json`, `.mcp.json`, les hooks ou les profils d'agents sont détectées mais ne sont pas injectées automatiquement comme texte de contexte.

Documentation détaillée : [Contexte natif des projets](docs/developer/native-context-sources.md).

## Périmètre actuel

- repositories locaux ;
- Java comme premier langage de code ;
- Markdown comme première source documentaire ;
- analyse Java structurelle avec JavaParser ;
- indexation SQLite + Lucene ;
- recherche BM25, symbolique et graphe ;
- ranking déterministe et explicable ;
- construction d'extraits sous budget ;
- instructions natives avec résolution de scope ;
- CLI humaine et JSON ;
- JAR autonome.

Les intégrations IDE, API REST, MCP, Git distant, multi-langage, skills actifs et embeddings restent des étapes ultérieures de la roadmap.

## Architecture

Le cœur reste en Java 21 sans framework applicatif obligatoire.

Principaux ports :

- `LanguageAnalyzer` ;
- `SearchStrategy` ;
- `ContextRanker` ;
- `TokenEstimator` ;
- `ContextBuilder` ;
- `ContextSourceProvider`.

```text
Formats et données natives
   │
   ├── Java
   ├── Markdown
   ├── AGENTS.md
   ├── Copilot instructions
   ├── CLAUDE.md
   └── GEMINI.md
   │
   ▼
Providers / analyzers
   │
   ▼
Modèle NEXUS normalisé
   │
   ├── SQLite canonique
   └── Lucene dérivé
   │
   ▼
Recherche + ranking + scope
   │
   ▼
Budget
   │
   ▼
ContextBundle
```

### Stockage et recherche

- SQLite : source de vérité structurelle locale ;
- Lucene : index de recherche reconstructible ;
- JGit : `.gitignore` / `.nexusignore` ;
- SHA-256 : détection incrémentale et déduplication ;
- JavaParser : analyse Java ;
- Jackson : uniquement à la frontière CLI JSON.

## Documentation

- [Architecture](docs/architecture.md)
- [Définition du MVP](docs/mvp.md)
- [Feuille de route](docs/roadmap.md)
- [Registre ADR](docs/adr/README.md)
- [Guide développeur](docs/developer/README.md)
- [CLI du MVP](docs/developer/cli-mvp.md)
- [Contexte natif des projets](docs/developer/native-context-sources.md)

Le guide développeur contient les diagrammes Mermaid/UML, les séquences d'exécution et les procédures permettant de reproduire l'implémentation.

## État du projet

### Itération 0 — Socle

**Terminée et validée localement.**

Java 21, Maven, contrats initiaux et premier analyseur JavaParser.

### Itération 1 — Indexation locale

**Terminée et validée localement.**

Registre de projets, scanner, ignore rules, SQLite, migrations, Lucene et indexation incrémentale.

Le self-smoke initial a également permis de détecter puis corriger la configuration du niveau Java 21 dans JavaParser.

### Itération 2 — Recherche et ranking

**Terminée et validée localement.**

BM25 multi-champs, recherche de symboles exacte/fuzzy, graphe d'imports, ranking déterministe et explication des scores.

### Itération 3 — Construction du contexte

**Terminée et validée localement.**

`ContextBuilder`, fragments symboliques, fusion, estimation de tokens, troncature et sélection sous budget.

Validation historique : 3 items, 178/180 tokens estimés avec environ 96,49 % de réduction du contexte candidat.

### Itération 4 — CLI utilisable pour le MVP

**Terminée et validée localement le 19 juillet 2026. Le MVP du moteur est validé de bout en bout.**

Validation :

- 66 fichiers source compilés ;
- 11 fichiers de test compilés ;
- 16 tests, 0 échec ;
- `mean precision@3 = 0,4444` ;
- `mean recall@3 = 1,0000` ;
- JAR bibliothèque et JAR CLI autonome ;
- self-smoke : 77 fichiers, 322 symboles, 599 relations ;
- indexation complète : 896 ms ;
- indexation incrémentale : 232 ms avec 0 changement ;
- recherche : 254 ms ;
- contexte : 285 ms ;
- résultat : `SELF-SMOKE SUCCESS`.

### Itération 5 — Instructions et documentation

**En cours — validation locale à effectuer.**

Implémentation actuelle :

- indexation des `.md` ordinaires comme `DOCUMENTATION` ;
- `MarkdownLanguageAnalyzer` ;
- `ContextSourceProvider` et `ContextSourceDescriptor` ;
- provider `AGENTS.md` et alias de compatibilité `AGENT.md` ;
- provider GitHub Copilot : repository-wide et `applyTo` ;
- provider Claude : `CLAUDE.md`, `.claude/CLAUDE.md` et scopes imbriqués ;
- provider Gemini : `GEMINI.md` ;
- priorité par spécificité du scope ;
- références `@fichier` confinées au repository, profondeur maximale 5 et détection de cycles ;
- déduplication SHA-256 entre instructions identiques ;
- déduplication entre documentation référencée et documentation retrouvée par Lucene ;
- sous-budget d'instructions : 25 % du budget total, plafonné à 600 tokens ;
- détection des settings, MCP, hooks, profils d'agents et skills sans injection brute ;
- catégories distinctes `INSTRUCTION`, `AGENT_PROFILE` et `SKILL` ;
- `AGENTS.md` racine dans NEXUS afin de dogfooder le mécanisme ;
- tests brownfield avec `.github`, `.claude`, instructions imbriquées et documentation ;
- self-smoke étendu à 11 étapes avec validation d'un bundle multi-source.

Décisions : ADR-0032 et ADR-0033.

## Comment utiliser NEXUS sur une application déjà configurée

Aucune migration n'est requise.

```powershell
.\scripts\nexus.ps1 project add N:\workspace-dev\mon-app mon-app
.\scripts\nexus.ps1 index mon-app
.\scripts\nexus.ps1 context mon-app "modifier OrderService" --budget 2000 --explain
```

NEXUS :

1. recherche les fichiers et symboles pertinents ;
2. utilise leurs chemins pour déterminer les instructions applicables ;
3. sélectionne les `AGENTS.md`, instructions Copilot, Claude ou Gemini concernées ;
4. ignore les instructions hors scope ;
5. détecte les configurations opérationnelles sans les injecter ;
6. ajoute la documentation Markdown pertinente ;
7. déduplique ;
8. respecte le budget global.

Pour inspecter précisément la sélection :

```powershell
$result = .\scripts\nexus.ps1 context mon-app "modifier OrderService" --budget 2000 --explain --json | ConvertFrom-Json
$result.items | Select-Object type, path, estimatedTokens
$result.metadata.nativeSourcesDiscovered
$result.metadata.nativeSourcesDeduplicated
$result.metadata.nativeCustomizationsDetected
```

## CLI

Classe principale :

```text
cli.com.nexus.NexusCli
```

Commandes :

```text
project add <chemin> [nom] [--json]
project list [--json]
index <id-ou-nom> [--rebuild] [--json]
search <id-ou-nom> <requête> [--limit N] [--explain] [--json]
context <id-ou-nom> <requête> [--budget N] [--explain] [--json]
inspect <id-ou-nom> [--json]
--help [--json]
--version [--json]
```

Sous Windows :

```powershell
.\scripts\nexus.ps1 --help
```

JAR autonome :

```powershell
java -jar .\target\nexus-context-engine-0.1.0-SNAPSHOT-cli.jar --version --json
```

## Validation locale de l'Itération 5

```powershell
git pull --ff-only
mvn clean install
.\scripts\self-smoke.ps1 -KeepData
```

Le self-smoke doit désormais vérifier :

```text
JAR autonome
   ↓
Java + Markdown indexés
   ↓
indexation incrémentale idempotente
   ↓
recherche explicable
   ↓
AGENTS.md natif sélectionné
   ↓
ContextBundle strict <= 180 tokens
   ↓
ContextBundle multi-source
   ├── INSTRUCTION
   ├── DOCUMENTATION
   └── code
   ↓
SELF-SMOKE SUCCESS
```

## Sécurité par défaut

NEXUS est local-first. Aucun contenu ne quitte la machine sans intégration externe explicitement activée.

- `.gitignore` et `.nexusignore` sont respectés ;
- les secrets et formats sensibles connus sont exclus ;
- les références `@fichier` ne peuvent pas sortir du repository ;
- les settings et permissions d'agents ne sont pas injectés comme contexte ;
- aucun hook ou serveur MCP n'est exécuté pendant la construction du contexte.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
