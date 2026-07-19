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
   ├── Agent Skills pertinents
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

Un projet qui possède déjà `AGENTS.md`, `.github/copilot-instructions.md`, `.github/instructions/*.instructions.md`, `CLAUDE.md`, `.claude/CLAUDE.md`, `GEMINI.md` ou des `SKILL.md` n'a pas besoin de migrer vers un format propriétaire.

NEXUS :

```text
instructions
→ découvre
→ résout le scope
→ sélectionne

skills
→ découvre les métadonnées
→ sélectionne name + description
→ charge seulement le SKILL.md pertinent
```

Les settings, permissions, hooks, configurations MCP et profils d'agents restent séparés du contexte automatiquement injecté.

Documentation détaillée :

- [Contexte natif des projets](docs/developer/native-context-sources.md)
- [Agent Skills](docs/developer/agent-skills.md)

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
- Agent Skills avec divulgation progressive ;
- CLI humaine et JSON ;
- JAR autonome.

Les intégrations IDE, API REST, MCP, Git distant, multi-langage, AI Skills Registry et embeddings restent des étapes ultérieures de la roadmap.

## Architecture

Le cœur reste en Java 21 sans framework applicatif obligatoire.

Principaux ports :

- `LanguageAnalyzer` ;
- `SearchStrategy` ;
- `ContextRanker` ;
- `TokenEstimator` ;
- `ContextBuilder` ;
- `ContextSourceProvider` ;
- `SkillSourceProvider`.

```text
Repository
   │
   ├── Java
   ├── Markdown
   ├── AGENTS.md / Copilot / Claude / Gemini
   └── Agent Skills
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
Recherche + ranking + scope + skill matching
   │
   ▼
Budgets
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
- SnakeYAML Engine : frontmatter YAML 1.2 des Agent Skills ;
- Jackson : frontière CLI JSON.

## Documentation

- [Architecture](docs/architecture.md)
- [Définition du MVP](docs/mvp.md)
- [Feuille de route](docs/roadmap.md)
- [Registre ADR](docs/adr/README.md)
- [Guide développeur](docs/developer/README.md)
- [CLI du MVP](docs/developer/cli-mvp.md)
- [Contexte natif des projets](docs/developer/native-context-sources.md)
- [Agent Skills](docs/developer/agent-skills.md)

## État du projet

### Itération 0 — Socle

**Terminée et validée.**

Java 21, Maven, contrats initiaux et JavaParser.

### Itération 1 — Indexation locale

**Terminée et validée.**

Registre de projets, scanner, ignore rules, SQLite, migrations, Lucene et indexation incrémentale.

### Itération 2 — Recherche et ranking

**Terminée et validée.**

BM25 multi-champs, recherche de symboles exacte/fuzzy, graphe d'imports, ranking déterministe et explication des scores.

### Itération 3 — Construction du contexte

**Terminée et validée.**

`ContextBuilder`, fragments symboliques, fusion, estimation de tokens, troncature et sélection sous budget.

### Itération 4 — CLI utilisable pour le MVP

**Terminée et validée le 19 juillet 2026. Le MVP du moteur est validé de bout en bout.**

Validation de référence :

- 66 sources ;
- 11 fichiers de test ;
- 16 tests verts ;
- `precision@3 = 0,4444` ;
- `recall@3 = 1,0000` ;
- JAR autonome ;
- `SELF-SMOKE SUCCESS`.

### Itération 5 — Instructions et documentation

**Terminée et validée le 20 juillet 2026.**

Validation de référence :

- 83 sources ;
- 13 fichiers de test ;
- 19 tests verts ;
- 145 fichiers indexés ;
- 406 symboles ;
- 781 relations ;
- Java + Markdown ;
- instructions natives sélectionnées ;
- contexte strict : 172/180 tokens ;
- contexte multi-source : 1 185/1 200 tokens ;
- `SELF-SMOKE SUCCESS`.

### Itération 6 — Skills et divulgation progressive

**Terminée et validée localement le 20 juillet 2026.**

Validation de référence :

- 100 fichiers source compilés avec Java 21 ;
- 17 fichiers de test compilés ;
- 26 tests exécutés, 0 échec, 0 erreur, 0 ignoré ;
- baseline qualité conservée : `precision@3 = 0,4444`, `recall@3 = 1,0000` ;
- 170 fichiers indexés ;
- 480 symboles ;
- 926 relations ;
- indexation complète : 1 218 ms ;
- indexation incrémentale : 282 ms avec 0 fichier modifié et 0 supprimé ;
- recherche `ProjectIndexingService` : 282 ms, fichier principal classé premier ;
- contexte strict : 5 items, 180/180 tokens, 454 ms ;
- contexte multi-source : 9 items, 1 185/1 200 tokens, 449 ms ;
- contexte avec skill : 1 194/1 200 tokens, 550 ms ;
- `nexus-context-validation` découvert, matché, activé et sélectionné ;
- `SKILL.md` complet inclus sans troncature : 233 tokens ;
- 1 ressource associée inventoriée mais non chargée automatiquement ;
- `skillsExecuted = false` ;
- réduction du contexte candidat strict : environ 99,14 % ;
- résultat final : `SELF-SMOKE SUCCESS`.

Le critère de sortie de l'Itération 6 est validé : NEXUS sait recommander et inclure un skill pertinent dans un `ContextBundle` sans charger tous les skills, sans tronquer le skill sélectionné et sans exécuter ses scripts.

L'Itération 6 est encadrée par ADR-0034. La prochaine cible de la roadmap est l'**Itération 7 — Contexte Git**.

## Comment utiliser NEXUS sur une application déjà configurée

```powershell
.\scripts\nexus.ps1 project add N:\workspace-dev\mon-app mon-app
.\scripts\nexus.ps1 index mon-app
.\scripts\nexus.ps1 context mon-app "extract PDF form" --budget 2000 --explain
```

NEXUS peut produire conceptuellement :

```text
ContextBundle
├── INSTRUCTION  AGENTS.md
├── SKILL        .agents/skills/pdf-processing/SKILL.md
├── SYMBOL       PdfService.java#extractForm
├── TEST         PdfServiceTest.java
└── DOCUMENTATION docs/pdf-processing.md
```

Une référence comme :

```text
.agents/skills/pdf-processing/references/forms.md
```

reste hors du bundle tant qu'un consommateur ne la demande pas explicitement.

## Inspecter les skills en JSON

```powershell
$result = .\scripts\nexus.ps1 context mon-app "extract PDF form" --budget 2000 --explain --json |
    ConvertFrom-Json

$result.items | Where-Object type -eq "SKILL"
$result.metadata.skillsDiscovered
$result.metadata.skillsMatched
$result.metadata.skillsSelected
$result.metadata.skillResourcesDiscovered
$result.metadata.skillsExecuted
```

La propriété :

```text
skillsExecuted = false
```

est un invariant de sécurité : NEXUS construit du contexte, il n'exécute pas les skills.

## CLI

Classe principale :

```text
com.nexus.cli.NexusCli
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

## Validation locale de référence

```powershell
git pull --ff-only
mvn clean install
.\scripts\self-smoke.ps1 -KeepData
```

Le self-smoke valide désormais :

```text
JAR autonome
   ↓
Java + Markdown
   ↓
instructions natives
   ↓
contexte multi-source
   ↓
Agent Skill découvert par metadata
   ↓
Skill sélectionné
   ↓
SKILL.md complet chargé après sélection
   ↓
ressources seulement inventoriées
   ↓
skillsExecuted = false
   ↓
SELF-SMOKE SUCCESS
```

## Sécurité par défaut

NEXUS est local-first.

- `.gitignore` et `.nexusignore` sont respectés ;
- les secrets et formats sensibles connus sont exclus ;
- les références d'instructions ne sortent pas du repository ;
- settings, permissions et profils d'agents ne sont pas injectés comme instructions ;
- aucun hook ni serveur MCP n'est exécuté ;
- aucun script d'un Agent Skill n'est exécuté ;
- les ressources d'un skill ne sont pas chargées automatiquement.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
