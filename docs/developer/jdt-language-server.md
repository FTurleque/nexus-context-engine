# Analyse Java profonde optionnelle avec Eclipse JDT Language Server

Ce chapitre décrit le contrat **courant** de l'intégration JDT LS dans NEXUS 0.2.0. JDT LS reste un provider externe opt-in : il enrichit l'intelligence Java sans devenir une dépendance obligatoire du chemin d'indexation normal.

## Positionnement

```text
JavaParser
→ structure Java embarquée, toujours disponible

SCIP
→ index externe importé opportunément lorsqu'un index.scip sûr est présent

JDT Language Server
→ provider Java profond lancé uniquement avec --deep-java
```

Le cœur conserve son propre modèle (`CodeSymbol`, `SymbolRelation`, `CodeIntelligenceSnapshot`) et ne persiste jamais des types JDT.

## Installation et intégrité

Sous Windows :

```powershell
.\scripts\install-jdtls.ps1
```

La version supportée et reproductible est actuellement :

```text
1.60.0-202606262232
```

Le script :

1. lit l'ancre `jdtls.<version>.sha256` dans `config/tool-integrity.properties` ;
2. télécharge uniquement l'archive JDT LS depuis Eclipse ;
3. calcule localement son SHA-256 ;
4. compare les octets téléchargés à **l'ancre versionnée dans le repository** ;
5. échoue fermé si l'ancre manque, est invalide ou ne correspond pas ;
6. extrait l'installation sous `~/.nexus/tools` puis positionne `NEXUS_JDTLS_HOME` pour le processus PowerShell courant.

NEXUS ne télécharge donc pas un checksum de confiance depuis le même origin que l'archive pour décider de son authenticité.

## Configuration

```text
NEXUS_JDTLS_HOME             racine JDT LS contenant plugins/ et config_<os>/
NEXUS_JDTLS_JAVA             java par défaut
NEXUS_JDTLS_TIMEOUT_SECONDS  120 par défaut
NEXUS_JDTLS_MAX_SYMBOLS      250 par défaut
```

Activation :

```powershell
nexus index mon-projet --deep-java
nexus index mon-projet --rebuild --deep-java
```

Sans `NEXUS_JDTLS_HOME`, une demande `--deep-java` échoue explicitement.

## Cycle de vie du snapshot

Une indexation normale conserve le snapshot JDT tant qu'aucun fichier Java canonique n'a changé. Dès qu'un fichier Java change ou disparaît, l'ancien snapshot JDT est purgé afin d'éviter des références/hiérarchies obsolètes. Une nouvelle analyse profonde le reconstruit.

## Transport JSON-RPC / LSP durci

JDT LS est lancé comme processus enfant en STDIO. `CLIENT_PORT` et `CLIENT_HOST` sont retirés de son environnement afin de forcer le transport local standard.

Le framing entrant est fail-closed et borné **avant allocation** :

```text
message JSON-RPC      <= 16 MiB
ensemble des headers  <= 64 KiB
une ligne de header   <= 8 KiB
messages en attente   <= 256
```

`Content-Length` absent, invalide, contradictoire ou supérieur à la limite est rejeté. Un header tronqué avant son terminateur de ligne est également rejeté. Si la file entrante est saturée, la session détruit le processus au lieu d'accumuler un backlog non borné.

Ces limites sont implémentées par `JdtJsonRpcFrameReader` et couvertes par des tests de non-régression.

## Bornes de travail externe

Les intégrations externes passent par `ExternalTaskRunner` :

- timeout global par tâche ;
- interruption du worker au timeout ;
- maximum **8 tâches externes réellement actives** à l'échelle JVM ;
- capacité rendue seulement lorsque le worker termine réellement ;
- saturation rejetée explicitement au lieu de créer des threads non bornés.

Un provider tiers peut toujours ignorer une interruption. NEXUS ne revendique donc pas une isolation processus absolue, mais l'accumulation de workers JVM est bornée.

## Workspace et lecture seule

Chaque projet utilise un workspace dédié sous :

```text
NEXUS_HOME/jdtls-workspaces/<identifiant-du-projet>
```

Le provider est read-only. Une requête `workspace/applyEdit` reçue du serveur est refusée.

Le processus JDT LS est arrêté après l'analyse profonde ; un daemon persistant n'est pas adopté sans justification mesurée.

## Requêtes LSP utilisées

```text
textDocument/documentSymbol
textDocument/references
textDocument/implementation
textDocument/prepareTypeHierarchy
typeHierarchy/supertypes
typeHierarchy/subtypes
textDocument/prepareCallHierarchy
callHierarchy/incomingCalls
callHierarchy/outgoingCalls
```

Les faits compatibles sont normalisés vers `CodeSymbol` et les relations NEXUS (`REFERENCES`, `IMPLEMENTS`, `EXTENDS`, `CALLS`) avec `sourceProvider=jdtls`.

## Qualification

Le contrat courant est couvert notamment par :

- tests du framing `JdtJsonRpcFrameReader` ;
- tests du provider JDT et de son cycle de vie ;
- tests de `ExternalTaskRunner` ;
- vérification de l'ancre JDT LS par `scripts/release/test-tool-integrity-anchors.sh` dans NEXUS CI.

Voir aussi [`code-intelligence.md`](code-intelligence.md), [`ci-and-supply-chain.md`](ci-and-supply-chain.md) et [`current-limitations.md`](current-limitations.md).
