# Release, installation et recovery

> État courant : Phase 6, hardening post-Phase 6, provenance des index, assistant Windows/Docker et consolidation post-audit sont intégrés dans `main`.

## Version produit

La baseline produit est **NEXUS Context Engine 0.2.0**.

Le reactor Maven est piloté par le `pom.xml` racine et contient :

- `core` — `io.github.fturleque:nexus-context-engine:0.2.0` ;
- `adapters/rest-quarkus` ;
- `adapters/mcp-java` ;
- `adapters/assistant-clients`.

Maven doit être exécuté avec un JDK 21 ou supérieur ; le bytecode et les API ciblent Java 21 via `maven.compiler.release=21`. Le wrapper est épinglé sur Maven 3.9.11.

## Livrables Maven

`mvnw.cmd clean install` produit notamment :

```text
target/
├── nexus-context-engine-0.2.0.jar
├── nexus-context-engine-0.2.0-cli.jar
├── nexus-context-engine-0.2.0-cli.jar.sha256
├── distribution/
│   ├── nexus-context-engine-0.2.0.zip
│   └── nexus-context-engine-0.2.0.zip.sha256
├── licenses/
│   └── THIRD_PARTY_NOTICES.txt
└── sbom/
    └── bom.json
```

Le ZIP multiplateforme fonctionne sans clone du dépôt ni Maven installé sur la machine cible, mais nécessite Java 21+.

NEXUS est un logiciel **propriétaire source-available**. `LICENSE` est la source de vérité pour les droits sur NEXUS ; les composants tiers restent soumis à leurs propres licences.

## Livrables Windows

La distribution Windows x64 self-contained produit :

```text
target\dist\nexus-context-engine-0.2.0-windows-x64.zip
target\dist\nexus-context-engine-0.2.0-windows-x64.zip.sha256
target\dist\NEXUS-0.2.0-windows-x64-setup.exe
target\dist\NEXUS-0.2.0-windows-x64-setup.exe.sha256
```

Le runtime Java est embarqué via `jpackage`. Le setup est current-user et supporte les modes :

```text
Natif Windows
Docker
Natif + Docker
```

REST est un composant natif optionnel ; CLI et MCP peuvent être installés sans REST. `NEXUS_HOME` est conservé lors de la désinstallation.

Voir [`../user/windows-installation.md`](../user/windows-installation.md).

## Livrable Docker

L'image NEXUS est construite depuis `packaging/docker` et qualifiée par le workflow **Docker Distribution**.

Le pipeline de publication sur `main` :

1. reconstruit l'image exacte du SHA ;
2. bloque les vulnérabilités HIGH/CRITICAL corrigibles ;
3. génère un SBOM CycloneDX image ;
4. publie les tags versionné et `latest` vers GHCR ;
5. résout le digest publié ;
6. atteste la provenance sur ce digest ;
7. atteste le SBOM sur ce même digest.

Une release conteneur ne doit pas être considérée comme qualifiée si le digest publié n'est pas celui couvert par les attestations.

## Intégrité et inventaire logiciel

Les livrables distribuables sont accompagnés de SHA-256 lorsque prévu par les scripts de release.

Le reactor génère un SBOM CycloneDX agrégé et les notices tierces :

```text
target/sbom/bom.json
target/licenses/THIRD_PARTY_NOTICES.txt
```

Le ZIP autonome embarque :

```text
LICENSE
THIRD_PARTY_NOTICES.txt
SBOM.cdx.json
```

La qualification Windows vérifie que les artefacts de conformité embarqués correspondent aux outputs générés.

L'image Docker possède son propre SBOM et ses propres preuves Trivy ; le SBOM du reactor et le SBOM de l'image répondent à deux périmètres différents et ne doivent pas être substitués l'un à l'autre.

## Gates release et supply-chain

La baseline active comprend :

- **NEXUS CI** : Windows Java 24 + Linux Java 21, reactor complet, tests, JaCoCo et compliance ;
- **Windows Installer** : build Windows, smoke install/execute/uninstall, setup production ;
- **Docker Distribution** : parité runtime, dotenv, Trivy, SBOM, publication/attestation sur `main` ;
- **Scale Benchmark** : SQLite, graphe, contexte fédéré ;
- **CodeQL** ;
- **OSV-Scanner** : delta PR + scan bloquant du SBOM agrégé du reactor.

Le gate JaCoCo du module `core` reste :

- lignes : minimum 70 % ;
- branches : minimum 50 %.

Aucun workflow/configuration/status SonarCloud actif n'est défini dans la baseline actuelle ; SonarCloud n'est donc pas un gate exécutable de release.

Voir [`ci-and-supply-chain.md`](ci-and-supply-chain.md).

## Migration SQLite

SQLite reste la source canonique. Les migrations sont forward-only et enregistrées dans `schema_migrations`.

Les migrations pertinentes comprennent notamment :

- `V002__index_generation.sql` — génération par projet pour invalider les vues dérivées ;
- `V003__provider_and_graph_indexes.sql` — indexes/déduplication nécessaires aux providers persistés et aux projections de graphe.

La génération d'index n'est plus incrémentée pour un no-op effectif : une génération doit représenter un changement canonique réel, pas une simple tentative d'indexation.

Avant une montée de version de production, sauvegarder SQLite lorsque le service est arrêté. Ne jamais restaurer uniquement les index Lucene en ignorant SQLite.

## Autorité et provenance des index

SQLite et l'état des fichiers canoniques restent l'autorité. Les index Lucene lexicaux/sémantiques et snapshots externes sont dérivés et reconstructibles.

Depuis PR #24 :

- un fingerprint canonique déterministe représente l'état pertinent des fichiers indexés ;
- un changement SOURCE/TEST invalide les snapshots persistés concernés ;
- l'index sémantique porte un manifeste de provenance ;
- une provenance absente ou incompatible entraîne un rebuild ;
- une recherche sémantique refuse un index dont la compatibilité n'est pas démontrée.

Depuis PR #49, l'indexation revalide aussi le snapshot canonique avant publication : si le repository a muté pendant l'opération, NEXUS échoue **fail-closed** plutôt que de publier un mélange d'états.

Voir [`../index-provenance.md`](../index-provenance.md).

## Concurrence et verrouillage

Une mutation d'index par projet est protégée à deux niveaux :

1. mutex JVM par `projectId` ;
2. `FileLock` OS par projet sous `NEXUS_HOME/locks`.

Cette coordination couvre les mutations d'index concernées par le même verrou. Le support cible reste un `NEXUS_HOME` sur filesystem local.

Le verrou empêche deux mutations simultanées du même projet ; la revalidation du snapshot protège en plus contre une **mutation externe du repository source** qui survient pendant l'indexation.

## Recovery d'indexation

En cas de panne pendant une indexation :

1. le projet est normalement marqué `FAILED` ;
2. un crash brutal peut laisser `INDEXING` persistant ;
3. les lectures indexées refusent un projet non `READY` ;
4. la prochaine indexation d'un projet non `READY` force une reconstruction complète ;
5. les index dérivés peuvent être reconstruits depuis SQLite et les fichiers canoniques.

En cas de détection d'une mutation concurrente du repository pendant l'indexation, corriger/stabiliser la source puis relancer l'indexation. Ne pas contourner cette erreur : elle protège contre la publication d'un snapshot incohérent.

## Recovery Lucene

En cas de corruption d'un index Lucene :

- arrêter les opérations concernées ;
- supprimer uniquement l'index dérivé corrompu ;
- conserver SQLite ;
- lancer un rebuild.

Pour un index sémantique sans provenance compatible, le comportement attendu est également un rebuild.

## Recovery Docker

`NEXUS_HOME` est persistant et doit être traité comme l'état à sauvegarder. Une image Docker est remplaçable ; les données persistantes ne le sont pas.

Pour diagnostiquer une release image :

- identifier le digest réellement exécuté ;
- vérifier qu'il correspond à l'image attendue ;
- utiliser les preuves Trivy/SBOM du workflow concerné ;
- pour une image publiée depuis `main`, vérifier les attestations rattachées au digest.

Ne pas considérer `latest` seul comme une preuve d'identité immuable.

## Sécurité REST en exploitation

La configuration locale par défaut écoute sur loopback et peut fonctionner sans token.

Une écoute hors loopback exige :

- `NEXUS_REST_API_TOKEN` robuste ;
- `NEXUS_REST_ALLOWED_PROJECT_ROOTS` non vide ;
- `NEXUS_REST_EXPOSURE_MODE=reverse-proxy-https|direct-https` ;
- ou `loopback-forward` uniquement dans le runtime Docker (`NEXUS_RUNTIME=docker`) pour une publication hôte maintenue sur loopback.

Le token distant doit contenir au moins 32 octets et atteindre une entropie estimée minimale de 96 bits. Les racines autorisées sont canonicalisées.

Un serveur qui refuse de démarrer après passage hors loopback doit être corrigé par configuration explicite ; désactiver ou contourner les gardes n'est pas une procédure de recovery valide.

## Qualification release Windows

Sous Windows, la qualification locale principale reste pilotée par :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Selon le périmètre de release, les scripts de `scripts/release/` ajoutent les contrôles Windows Installer, Docker et configuration.

En CI, `NEXUS_EXPECTED_HEAD_SHA` permet d'imposer l'exact-head lorsque le workflow le prévoit.

## Preuve post-audit de référence

PR #49 :

```text
QUALIFIED_HEAD=4f04c1ad3ff5b41aa9d1892ade57ad62b90a43f9
MERGE_SHA=c1ff9ef03ef33097c0d51154e02c30109b0a46f1
```

- NEXUS CI `31314135008` — PASS ;
- Windows Installer `31314134983` — PASS ;
- Docker Distribution `31314134994` — PASS ;
- Scale Benchmark `31314135000` — PASS ;
- CodeQL `31314134977` — PASS ;
- OSV-Scanner `31314135231` — PASS.

Le Scale Benchmark a été rerun une seule fois sur le même HEAD après qualification d'un outlier I/O runner ; aucun budget n'a été assoupli.

Une release, un tag ou un merge ne doit jamais être déclaré qualifié sans preuve exécutable rattachée au HEAD concerné.
