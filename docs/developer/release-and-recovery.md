# Release, installation et recovery

> État courant : Phase 6, hardening post-Phase 6, licence propriétaire et hardening de provenance des index sont intégrés dans `main`. La qualification exacte-head de PR #24 a exécuté avec succès le gate Windows Java 24 et le reactor/smoke de distribution Linux Java 21.

## Version produit

La baseline produit est **NEXUS Context Engine 0.2.0**.

Le reactor Maven est piloté par le `pom.xml` racine et contient :

- `core` — `io.github.fturleque:nexus-context-engine:0.2.0` ;
- `adapters/rest-quarkus` ;
- `adapters/mcp-java` ;
- `adapters/assistant-clients`.

Maven doit être exécuté avec un JDK 21 ou supérieur ; le bytecode et les API restent ciblés sur Java 21 via `maven.compiler.release=21`. Maven est reproductible via `mvnw.cmd` / `mvnw`, épinglé sur Maven 3.9.11.

## Livrables

`mvnw.cmd clean install` produit notamment :

```text
target/
├── nexus-context-engine-0.2.0.jar
├── nexus-context-engine-0.2.0-cli.jar
├── nexus-context-engine-0.2.0-cli.jar.sha256
├── distribution/
│   ├── nexus-context-engine-0.2.0.zip
│   └── nexus-context-engine-0.2.0.zip.sha256
└── sbom/
    └── bom.json
```

L'archive autonome contient :

```text
nexus-context-engine-0.2.0/
├── bin/
│   ├── nexus.cmd
│   └── nexus
├── lib/
│   └── nexus-cli.jar
├── LICENSE
└── README.md
```

Elle fonctionne sans clone du dépôt et sans Maven installé sur la machine cible. Une JVM Java 21 ou supérieure reste requise.

NEXUS est un logiciel **propriétaire source-available**. Le fichier `LICENSE` livré avec le dépôt et la distribution est la source de vérité pour les droits accordés sur NEXUS. Les composants tiers restent soumis à leurs propres licences ; les notices tierces complètes sont suivies dans l'issue #22.

## Intégrité et inventaire logiciel

Les deux livrables distribuables sont accompagnés de SHA-256. Le build génère aussi un SBOM CycloneDX agrégé de l'ensemble du reactor.

Aucun secret, requête utilisateur, contenu source ou chemin de projet n'est injecté dans le SBOM.

## Migration SQLite

SQLite reste la source canonique. Les migrations sont forward-only et enregistrées dans `schema_migrations`.

La Phase 6 ajoute `V002__index_generation.sql` :

- génération monotone par projet pour invalider les vues dérivées ;
- index SQL pour les recherches de relations ciblées ;
- initialisation des projets existants sans écraser leur état.

Avant une montée de version de production, sauvegarder le fichier SQLite NEXUS lorsque le service est arrêté. Ne jamais restaurer uniquement les index Lucene en ignorant SQLite.

## Autorité et provenance des index

SQLite et l'état des fichiers canoniques restent l'autorité. Les index Lucene lexicaux et sémantiques ainsi que les snapshots d'intelligence externes sont dérivés/reconstructibles.

Depuis PR #24 :

- un fingerprint canonique déterministe représente l'état pertinent des fichiers indexés ;
- un changement SOURCE/TEST invalide les snapshots persistés des providers externes non embarqués ;
- l'index sémantique porte un manifeste Lucene avec fingerprint canonique, provider, modèle, dimensions, profil de préparation et version de schéma ;
- une provenance absente ou incompatible entraîne un rebuild sémantique ;
- une recherche sémantique ne consomme pas un index dont la compatibilité avec l'état canonique courant n'est pas démontrée.

Voir [`../index-provenance.md`](../index-provenance.md).

## Concurrence et verrouillage

Une mutation d'index par projet est protégée à deux niveaux :

1. mutex JVM par `projectId` ;
2. `FileLock` OS par projet sous `NEXUS_HOME/locks`.

Cette coordination couvre les mutations d'index, rebuilds/deep-Java et imports MINOS concernés par le même verrou de mutation. Le fichier de lock n'est pas un marqueur métier : sa simple présence ne signifie pas qu'un verrou est actif.

Le support cible est un `NEXUS_HOME` sur filesystem **local**. NEXUS ne revendique pas de garantie de cohérence inter-processus sur des filesystems réseau dont les sémantiques de `FileLock` diffèrent ou ne sont pas garanties.

## Recovery

En cas de panne pendant une indexation :

1. le projet est normalement marqué `FAILED` ;
2. un crash brutal peut laisser `INDEXING` persistant ;
3. les recherches et ContextBundle refusent tout projet qui n'est pas `READY` ;
4. la prochaine indexation d'un projet non-`READY` force une reconstruction complète ;
5. le verrou JVM + OS évite deux mutations concurrentes actives du même projet sur un `NEXUS_HOME` local partagé entre processus.

En cas de corruption d'un index Lucene, supprimer uniquement l'index dérivé puis lancer un rebuild. En cas de corruption SQLite, restaurer la sauvegarde canonique puis reconstruire les index dérivés.

Pour un index sémantique ancien sans métadonnées de provenance compatibles, le comportement attendu est également un rebuild : l'absence de preuve de compatibilité n'autorise pas sa réutilisation.

## Qualification release

Sous Windows, la qualification est pilotée par :

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\validate-phase-6.ps1
```

Le script impose :

- une JVM d'exécution Java 21 ou supérieure et `maven.compiler.release=21` ;
- Maven Wrapper fonctionnel (`mvnw.cmd` / `mvnw`) ;
- `clean install` du reactor complet ;
- `scripts/self-smoke.ps1` ;
- existence et vérification des SHA-256 ;
- SBOM CycloneDX ;
- extraction et exécution Windows de l'archive autonome, contrôle POSIX et smoke réel dans la CI Linux ;
- exact-head SHA vérifié via `NEXUS_EXPECTED_HEAD_SHA`.

Preuve récente : PR #24, head `25c12b100b774a4ec3d69d221675bf31d8ebaa0c`, NEXUS CI run #15 : Windows Java 24 PASS, Linux Java 21 Maven reactor PASS et distribution smoke PASS.

Une release, un tag ou un merge ne doit pas être déclaré qualifié sans preuve exécutable sur le head concerné. Les anciens runs à zéro étape (`steps=[]`) ne constituent pas une preuve de succès ou d'échec applicatif.
