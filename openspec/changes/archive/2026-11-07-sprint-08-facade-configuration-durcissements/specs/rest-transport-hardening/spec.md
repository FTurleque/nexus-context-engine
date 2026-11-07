# Spec Delta

## Purpose

Définit les contrôles de transport que l'adaptateur REST applique à chaque requête, indépendamment de l'authentification, afin qu'un listener loopback ne réponde qu'à des clients locaux.

## ADDED Requirements

### Requirement: Un listener loopback n'accepte que des hôtes loopback
Lorsque le listener REST applicatif écoute sur une adresse loopback, y compris en mode `NEXUS_REST_TRUST_LOCAL=true`, l'adaptateur SHALL refuser par une réponse 403 toute requête dont l'en-tête `Host` ne désigne pas `127.0.0.1`, `localhost` ou `[::1]`, avec ou sans port.

#### Scenario: Hôte loopback accepté
- **WHEN** le mode trust-local est actif et une requête `GET /api/v1/projects` porte `Host: 127.0.0.1:8080`
- **THEN** la requête est traitée normalement

#### Scenario: Hôte non loopback refusé en trust-local
- **WHEN** le mode trust-local est actif et une requête porte un en-tête `Host` désignant un autre nom que `127.0.0.1`, `localhost` ou `[::1]`
- **THEN** la réponse est 403, aucun projet n'est lu ni enregistré

#### Scenario: Refus sans détail
- **WHEN** une requête est refusée pour son hôte
- **THEN** le corps de la réponse ne contient ni la valeur reçue ni la configuration attendue

### Requirement: Un listener loopback refuse les origines web non loopback
Lorsque le listener REST applicatif écoute sur une adresse loopback, l'adaptateur SHALL refuser par une réponse 403 toute requête qui porte un en-tête `Origin` dont l'hôte n'est pas loopback, et SHALL accepter une requête sans en-tête `Origin`.

#### Scenario: Client sans origine
- **WHEN** un client en ligne de commande appelle l'API en trust-local sans en-tête `Origin`
- **THEN** la requête est traitée normalement

#### Scenario: Origine distante
- **WHEN** une requête porte un en-tête `Origin` dont l'hôte n'est pas loopback
- **THEN** la réponse est 403

### Requirement: Le contrôle de transport ne remplace pas le garde d'exposition
Hors loopback, l'adaptateur SHALL continuer d'exiger au démarrage le jeton, l'allowlist de racines et le TLS effectif ; le contrôle d'hôte et d'origine SHALL NOT assouplir ces exigences.

#### Scenario: Écoute réseau sans jeton
- **WHEN** le listener applicatif est configuré hors loopback sans jeton
- **THEN** le démarrage échoue comme avant, quel que soit le contrôle d'hôte
