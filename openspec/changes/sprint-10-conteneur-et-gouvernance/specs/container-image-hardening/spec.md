# Spec Delta

## Purpose

Définit les propriétés de l'image Docker de NEXUS et de son exécution par le compose officiel : code immuable pour l'UID d'exécution, contexte de build réduit, notices et métadonnées publiées, sonde de santé et privilèges minimaux.

## ADDED Requirements

### Requirement: Le code de l'image n'est pas modifiable par l'UID d'exécution
Les images construites par `Dockerfile` et `Dockerfile.runtime` SHALL donner à root la propriété de `/opt/nexus` et des scripts de `/usr/local/bin`, et SHALL réserver l'écriture de l'UID 10001 à `/data/nexus` et `/workspace`.

#### Scenario: Écriture refusée sur le code
- **WHEN** un processus de l'UID 10001 teste l'écriture sur `/opt/nexus/lib/nexus-cli.jar` ou `/usr/local/bin/nexus-entrypoint`
- **THEN** le test échoue, et le même test sur `/data/nexus` réussit

#### Scenario: Contrat sur les Dockerfiles
- **WHEN** un `COPY --chown=10001` vers `/opt/nexus` ou `/usr/local/bin` est ajouté à l'un des Dockerfiles
- **THEN** `scripts/release/test-operational-doc-contracts.sh` échoue

### Requirement: Le contexte de build ne contient que les entrées du build
Le build par `Dockerfile` SHALL exclure l'historique Git, les sorties `target/`, les fichiers d'environnement locaux et les données locales ; le build par `Dockerfile.runtime` SHALL n'accepter que les fichiers qu'il copie.

#### Scenario: Historique Git absent de l'étape build
- **WHEN** l'étape `build` de `Dockerfile` est construite depuis la racine du dépôt
- **THEN** `/workspace/.git` n'existe pas dans cette étape et le build Maven réussit

#### Scenario: Fichiers d'installation non copiés dans le contexte runtime
- **WHEN** l'image est reconstruite par le compose depuis une racine d'installation contenant `docker/.env` et un répertoire de données
- **THEN** ni ce fichier ni ce répertoire ne font partie du contexte de build

### Requirement: L'image publie ses notices et ses métadonnées
L'image SHALL contenir `/opt/nexus/LICENSE` et `/opt/nexus/THIRD_PARTY_NOTICES.txt` et SHALL porter les labels `org.opencontainers.image.source`, `org.opencontainers.image.licenses`, `org.opencontainers.image.revision` et `org.opencontainers.image.version`.

#### Scenario: Inspection de l'image construite par la CI
- **WHEN** le workflow `Docker distribution` inspecte l'image construite pour un commit
- **THEN** les deux fichiers sont non vides et `org.opencontainers.image.revision` vaut le SHA du commit

### Requirement: La sonde de santé et les privilèges minimaux sont déclarés
L'image SHALL déclarer un `HEALTHCHECK` qui appelle `/usr/local/bin/nexus-healthcheck`, et le compose officiel SHALL déclarer `read_only: true`, un `tmpfs` sur `/tmp`, `cap_drop: [ALL]` et `security_opt: [no-new-privileges:true]`.

#### Scenario: Conteneur lancé sans compose
- **WHEN** l'image est lancée par `docker run` en mode REST
- **THEN** Docker rapporte un état de santé `healthy` sans configuration supplémentaire

#### Scenario: Conteneur lancé par le compose
- **WHEN** le service est démarré par le compose officiel
- **THEN** la sonde rapporte `UP` alors que la racine du conteneur est en lecture seule et qu'aucune capacité n'est conservée
