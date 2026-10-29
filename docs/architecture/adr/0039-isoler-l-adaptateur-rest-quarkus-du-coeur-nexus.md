# ADR-0039 — Isoler l'adaptateur REST Quarkus du cœur NEXUS

- Statut : accepted
- Date : 2026-07-20

## Contexte et problème

Le moteur NEXUS est volontairement indépendant des frameworks applicatifs. Jusqu'à l'Itération 10, le cœur et la CLI sont construits par le `pom.xml` racine et le JAR bibliothèque peut être consommé sans serveur HTTP.

L'Itération 11 doit exposer les capacités NEXUS à d'autres applications via une API REST, avec santé et observabilité, sans transformer Quarkus en dépendance obligatoire du moteur.

Ajouter directement Quarkus au `pom.xml` racine introduirait un framework applicatif dans le classpath du cœur et mélangerait les responsabilités du moteur, de la CLI et du transport HTTP.

## Facteurs de décision

- préserver l'ADR-0003 : cœur Java sans framework applicatif obligatoire ;
- préserver l'usage bibliothèque et CLI existant ;
- isoler les DTO et contrats HTTP des modèles du cœur ;
- réutiliser les mêmes services métier et adapters de persistance/recherche ;
- disposer d'un runtime HTTP maintenable avec santé et métriques ;
- permettre à l'adaptateur MCP futur de rester indépendant de REST ;
- limiter le coût de migration du repository existant vers une structure multi-module complète.

## Options envisagées

### Option A — Ajouter Quarkus au module Maven racine

Avantages :

- build unique ;
- configuration Maven simple à première vue.

Inconvénients :

- Quarkus devient une dépendance du cœur ;
- mélange entre moteur, CLI et serveur ;
- risque de coupler progressivement les cas d'usage aux annotations CDI/REST ;
- va à l'encontre de l'indépendance du cœur.

### Option B — Convertir immédiatement tout le repository en agrégateur Maven multi-module

Avantages :

- séparation structurelle maximale ;
- build réacteur unique.

Inconvénients :

- déplacement massif du code existant ;
- changement des coordonnées et chemins de build à gérer ;
- risque de régression sans bénéfice direct pour le périmètre de l'Itération 11.

### Option C — Ajouter un sous-projet Maven autonome `adapters/rest-quarkus`

Avantages :

- le cœur reste inchangé et framework-agnostique ;
- l'adaptateur dépend du JAR NEXUS, jamais l'inverse ;
- Quarkus peut évoluer indépendamment ;
- la CLI conserve son packaging actuel ;
- une migration future vers un agrégateur Maven reste possible.

Inconvénients :

- le build de validation doit installer d'abord le cœur puis construire l'adaptateur ;
- deux commandes Maven sont nécessaires hors script d'orchestration.

## Décision retenue

Adopter l'option C.

L'adaptateur REST est implémenté dans :

```text
adapters/rest-quarkus/
```

Il possède son propre `pom.xml` et dépend de :

```text
io.github.fturleque:nexus-context-engine:0.1.0-SNAPSHOT
```

Le `pom.xml` racine ne reçoit aucune dépendance Quarkus.

La version initiale retenue pour l'adaptateur est Quarkus **3.33 LTS**, figée sur la micro-version **3.33.2.1** au démarrage de l'Itération 11.

Les responsabilités sont séparées ainsi :

```text
NEXUS core
→ modèles métier
→ repositories / ports
→ indexation
→ recherche
→ ranking
→ ContextBuilder

REST adapter
→ composition du runtime NEXUS
→ DTO HTTP
→ mapping domaine ↔ DTO
→ ressources REST
→ gestion des erreurs HTTP
→ santé
→ métriques
```

Les ressources REST ne contiennent aucune logique métier d'indexation, recherche ou construction de contexte.

## Contrat HTTP initial

Le contrat est versionné sous `/api/v1`.

Il expose :

- projets : création, lecture et liste ;
- indexation : indexation normale, reconstruction et analyse Java profonde optionnelle ;
- inspection des statistiques d'index ;
- recherche ;
- construction de contexte ;
- variantes d'explication explicites ;
- santé via SmallRye Health ;
- métriques via Micrometer/Prometheus.

Les DTO REST sont définis dans l'adaptateur. Aucun modèle du cœur n'est utilisé comme contrat HTTP public.

## Conséquences

### Positives

- Quarkus reste optionnel ;
- le cœur demeure réutilisable comme bibliothèque ;
- la CLI reste autonome ;
- les frontières ports/adapters sont préservées ;
- le futur adaptateur MCP peut réutiliser le cœur sans dépendre de REST ;
- le contrat API peut évoluer avec sa propre version.

### Négatives

- le câblage applicatif du moteur reste actuellement dupliqué entre la CLI et l'adaptateur REST ;
- une factory de composition commune pourra être extraite si cette duplication devient coûteuse ;
- la validation complète nécessite un build du cœur puis de l'adaptateur.

## Confirmation du respect de la décision

La décision est respectée si :

1. `mvn clean install` à la racine continue de construire le cœur sans Quarkus ;
2. `adapters/rest-quarkus/pom.xml` contient seul les dépendances Quarkus ;
3. l'adaptateur se construit après installation locale du JAR cœur ;
4. les ressources REST délèguent les opérations au service d'adaptation ;
5. les réponses HTTP utilisent des DTO dédiés ;
6. les tests Quarkus valident projets, indexation, recherche, contexte, explication, santé et métriques ;
7. le self-smoke historique du cœur reste vert.

## Analyse détaillée et réexamen

Cette décision devra être réexaminée si plusieurs adaptateurs nécessitent un build réacteur commun ou si la duplication de composition entre CLI, REST et MCP devient significative.

À ce moment, une migration contrôlée vers un parent Maven agrégateur avec modules `core`, `cli`, `rest-adapter` et `mcp-adapter` pourra être décidée par un nouvel ADR, sans réécrire rétroactivement cette décision.
