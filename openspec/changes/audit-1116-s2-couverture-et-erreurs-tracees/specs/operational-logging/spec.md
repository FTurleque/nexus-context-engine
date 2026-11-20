# Spec Delta

## Purpose

Définit ce que NEXUS journalise côté serveur pour qu'un opérateur diagnostique une erreur interne REST ou MCP et une disponibilité dégradée sans que la réponse publique expose un détail interne, et comment se règlent les journaux de la CLI et du serveur MCP.
Documentation durable : docs/developer/reproduce-and-debug.md#openspec-operational-logging

## ADDED Requirements

### Requirement: Une erreur interne REST est journalisée avec un identifiant renvoyé au client
Quand l'adaptateur REST convertit une exception en réponse HTTP 500, il SHALL journaliser côté serveur, au niveau erreur, l'exception avec sa pile et un identifiant de corrélation, et SHALL renvoyer ce même identifiant dans le corps JSON de la réponse, à côté de `error` et `message`.

#### Scenario: Erreur d'entrée/sortie
- **GIVEN** un traitement REST qui lève une `IOException` dont le message contient un chemin absolu de la machine
- **WHEN** la réponse est produite
- **THEN** le statut est 500, `error` vaut `io_error` et le corps porte un identifiant de corrélation non vide
- **AND** un enregistrement de journal de niveau erreur contient cet identifiant et l'exception d'origine

#### Scenario: Erreur interne d'état
- **GIVEN** une `IllegalStateException` qui n'est ni un projet non prêt ni une mutation d'index en cours
- **WHEN** la réponse est produite
- **THEN** le statut est 500, `error` vaut `internal_state_error` et le corps porte un identifiant de corrélation
- **AND** un enregistrement de journal de niveau erreur contient cet identifiant et l'exception d'origine

#### Scenario: Conflit de cycle de vie
- **GIVEN** une `IllegalStateException` reconnue comme projet non prêt ou mutation d'index en cours
- **WHEN** la réponse est produite
- **THEN** le statut reste 409, avec le même corps qu'avant ce changement
- **AND** aucun enregistrement de niveau erreur n'est produit

#### Scenario: Erreur de requête
- **WHEN** une requête REST invalide produit une réponse 400
- **THEN** la réponse ne porte pas d'identifiant de corrélation et aucun enregistrement de niveau erreur n'est produit

### Requirement: Une erreur interne d'outil MCP est journalisée avec un identifiant renvoyé au client
Quand un outil MCP échoue sur une exception autre qu'une `IllegalArgumentException`, ou quand sa réponse ne peut pas être sérialisée, le serveur SHALL journaliser l'exception avec sa pile et un identifiant de corrélation, et SHALL renvoyer ce même identifiant dans le résultat d'erreur de l'outil, à côté de `error` et `message`.

#### Scenario: Exception inattendue d'un outil
- **GIVEN** un outil dont le traitement lève une `RuntimeException` qui porte un chemin absolu de la machine
- **WHEN** l'outil est appelé
- **THEN** le résultat est en erreur, `error` vaut `nexus_tool_error`, `message` vaut « Erreur interne NEXUS » et le résultat porte un identifiant de corrélation
- **AND** un enregistrement de journal de niveau erreur contient cet identifiant et l'exception d'origine

#### Scenario: Opération indisponible dans l'état courant
- **GIVEN** un outil dont le traitement lève une `IllegalStateException`
- **WHEN** l'outil est appelé
- **THEN** le résultat porte un identifiant de corrélation et un enregistrement de journal contient cet identifiant et l'exception

#### Scenario: Argument invalide
- **WHEN** un outil est appelé avec une propriété JSON inconnue
- **THEN** le résultat est en erreur sans identifiant de corrélation et aucun enregistrement de niveau erreur n'est produit

#### Scenario: Réponse non sérialisable
- **GIVEN** un outil dont la réponse ne peut pas être sérialisée en JSON
- **WHEN** l'outil est appelé
- **THEN** le résultat vaut `serialization_error`, porte un identifiant de corrélation, et un enregistrement de journal contient cet identifiant et l'exception

### Requirement: La journalisation n'étend pas la sortie publique d'erreur
Une réponse d'erreur REST ou MCP qui porte un identifiant de corrélation SHALL NOT contenir de chemin absolu, de pile, de nom de classe ni de message d'exception interne. L'identifiant SHALL être généré aléatoirement à chaque erreur et SHALL NOT dériver de la requête, de l'exception ou d'un chemin.

#### Scenario: Chemin et pile absents de la réponse
- **GIVEN** une erreur interne REST ou MCP dont l'exception porte le chemin `C:\private\workspace\secret.txt`
- **WHEN** la réponse est produite
- **THEN** la réponse ne contient ni ce chemin, ni `stackTrace`, ni le nom de la classe de l'exception

#### Scenario: Deux erreurs identiques
- **WHEN** la même erreur interne se produit deux fois
- **THEN** les deux réponses portent deux identifiants différents, chacun retrouvé dans un enregistrement de journal distinct

### Requirement: Une disponibilité dégradée journalise sa cause
Quand la sonde de disponibilité conclut qu'un index dérivé d'un projet `READY` est indisponible à cause d'une exception, ou quand la sonde REST elle-même échoue, NEXUS SHALL journaliser l'exception avec sa pile et l'identifiant du projet concerné quand il est connu.

#### Scenario: Lecture de l'index dérivé en échec
- **GIVEN** un projet `READY` dont la vérification de présence de l'index lexical lève une `IOException`
- **WHEN** la disponibilité est calculée
- **THEN** l'instantané est marqué dégradé, comme avant ce changement
- **AND** un enregistrement de journal de niveau avertissement contient l'identifiant du projet et l'exception

#### Scenario: Sonde REST en échec
- **GIVEN** un calcul de disponibilité qui lève une `RuntimeException`
- **WHEN** la sonde de disponibilité REST est appelée
- **THEN** la sonde répond `DOWN`
- **AND** un enregistrement de journal contient l'exception

#### Scenario: Index dérivé absent sans exception
- **GIVEN** un projet `READY` dont l'index lexical est simplement absent
- **WHEN** la disponibilité est calculée
- **THEN** l'instantané est marqué dégradé et aucun enregistrement d'exception n'est produit

### Requirement: Les messages SLF4J des dépendances atteignent le journal de la CLI et du serveur MCP
Le JAR CLI et le JAR du serveur MCP SHALL embarquer un fournisseur SLF4J qui transmet les messages des dépendances au journal de la plateforme Java, et SHALL NOT retomber sur l'implémentation NOP.

#### Scenario: Commande CLI qui charge une dépendance SLF4J
- **GIVEN** un projet Git enregistré et indexé
- **WHEN** la commande `context` de la CLI s'exécute sur ce projet depuis le JAR de la distribution
- **THEN** stderr ne contient pas « No SLF4J providers were found »

#### Scenario: Message d'une dépendance
- **GIVEN** le niveau de journalisation réglé sur le niveau debug
- **WHEN** une dépendance journalise un message de niveau debug par SLF4J pendant une commande CLI ou une session MCP
- **THEN** le message apparaît sur stderr

### Requirement: Le niveau de journalisation de la CLI et du serveur MCP se règle par une variable documentée
La CLI et le serveur MCP SHALL lire au démarrage la variable d'environnement `NEXUS_LOG_LEVEL` et SHALL régler sur sa valeur le niveau des journaux qu'ils émettent. Sans variable, le niveau SHALL rester celui par défaut de la plateforme Java. La variable et ses valeurs SHALL être décrites dans la documentation utilisateur et développeur.

#### Scenario: Passage en debug
- **GIVEN** `NEXUS_LOG_LEVEL=DEBUG`
- **WHEN** le serveur MCP démarre
- **THEN** les enregistrements de niveau debug de NEXUS et de ses dépendances sont émis sur stderr

#### Scenario: Valeur inconnue
- **GIVEN** `NEXUS_LOG_LEVEL` vaut une chaîne qui n'est pas un niveau reconnu
- **WHEN** la CLI démarre
- **THEN** la commande s'exécute avec le niveau par défaut et stderr porte un avertissement en français qui rappelle les valeurs acceptées
- **AND** le code de sortie de la commande n'est pas modifié par cette valeur

#### Scenario: Variable absente
- **WHEN** la CLI démarre sans `NEXUS_LOG_LEVEL`
- **THEN** aucun enregistrement de niveau debug n'est émis

### Requirement: Les journaux de la CLI et du serveur MCP ne s'écrivent pas sur stdout
Les enregistrements de journal de la CLI et du serveur MCP, à tout niveau, SHALL être écrits sur stderr et SHALL NOT être écrits sur stdout, réservé aux données de la CLI et au framing JSON-RPC du serveur MCP.

#### Scenario: Session MCP en debug
- **GIVEN** `NEXUS_LOG_LEVEL=DEBUG`
- **WHEN** un client MCP initialise une session et appelle `list_projects`
- **THEN** chaque ligne de stdout est un message JSON-RPC valide
- **AND** les enregistrements de journal se trouvent sur stderr

#### Scenario: Commande CLI en JSON
- **GIVEN** `NEXUS_LOG_LEVEL=DEBUG`
- **WHEN** une commande CLI s'exécute avec la sortie JSON
- **THEN** stdout se lit comme un unique document JSON
