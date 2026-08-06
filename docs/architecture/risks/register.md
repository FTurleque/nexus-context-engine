# Registre des risques — NEXUS Context Engine

Ce registre complète la Section 11 de l'arc42. Voir [`arc42/11-risques-dette.md`](../arc42/11-risques-dette.md) pour le contexte architectural détaillé.

## État courant des risques prioritaires

### R12 — Qualification post-Phase 6 non exécutée (**CLÔTURÉ**)

- **Statut** : fermé ; ce risque ne doit plus être présenté comme un gate en attente.
- **Preuves historiques** : gates A–D et self-smoke post-Phase 6 exécutés avec succès en 2026-08-05.
- **Preuve récente** : PR #28 qualifiée sur Windows Java 24, Linux Java 21, OSV et CodeQL.

### R1 — Scale SQLite lexical (WATCH ITEM)

- **Probabilité** : Moyenne
- **Impact** : Moyen
- **Action requise** : benchmark sur corpus de 10k, 100k, 500k et 1M symboles avant toute décision de changer la stratégie ; suivi par l'issue #23.
- **Déclencheur de réexamen** : dégradation mesurée des temps de réponse > 2× ou dépassement d'un SLO défini.
- **Décision courante** : aucun FTS5/trigram/nouveau moteur sans mesure démontrant un bénéfice matériel.

### R4 — `FileLock` sur filesystem réseau (NON-SUPPORT DOCUMENTÉ)

- **Probabilité** : Moyenne si `NEXUS_HOME` est placé sur un stockage partagé/réseau.
- **Impact** : Élevé en cas de sémantique de verrouillage insuffisante.
- **Mitigation actuelle** : le support cible est un `NEXUS_HOME` local ; l'exclusion mutuelle inter-processus combine mutex JVM et `FileLock` OS par projet.
- **Action future possible** : détection/refus explicite au démarrage si une détection portable et fiable devient possible.

### R13 — Intelligence externe obsolète (**CLÔTURÉ par PR #24**)

- **Risque initial** : snapshots JDT/MINOS/autres providers persistés et encore consultables après changement canonique.
- **Mitigation** : changement SOURCE/TEST ⇒ invalidation des snapshots non embarqués persistés, même lorsque le provider n'est plus actif dans le runtime courant.
- **Preuve** : `ExternalCodeIntelligenceInvalidationTest` + qualification exacte-head de PR #24.

### R14 — Index sémantique incompatible (**CLÔTURÉ par PR #24**)

- **Risque initial** : réutilisation de vecteurs construits pour un autre état canonique, provider, modèle, dimension ou profil de contenu.
- **Mitigation** : manifeste de provenance Lucene ; mismatch/absence ⇒ rebuild ; recherche stale refusée avant calcul d'embedding de requête.
- **Preuve** : `SemanticIndexProvenanceIntegrationTest` + qualification exacte-head de PR #24.

### R15 — Supply-chain / obligations tierces (**CLÔTURÉ par PR #28**)

- **Risque initial** : distribution publique sans gate de couverture, analyse de vulnérabilités/code, pins immuables, conservation SBOM ni notices tierces matérialisées.
- **Mitigations intégrées** :
  - JaCoCo core 70 % lignes / 50 % branches ;
  - OSV-Scanner PR + scan courant/hebdomadaire ;
  - CodeQL Java/Kotlin `security-extended` ;
  - Dependabot Maven/GitHub Actions ;
  - Actions contrôlées épinglées à des SHA immuables ;
  - `THIRD_PARTY_NOTICES.txt` avec `failOnMissing=true` ;
  - `LICENSE`, notices et SBOM embarqués dans le ZIP ;
  - artefact CI de preuve conservé 90 jours.
- **Preuve** : PR #28 head `a363e93dc97597d288389b4f4b9e8404abe4296c` — NEXUS CI #31 PASS, OSV #4 PASS, CodeQL #6 PASS.
- **Risque résiduel** : toute nouvelle dépendance sous licence inhabituelle/copy-left fort doit faire l'objet d'une revue explicite de compatibilité ; l'automatisation garantit l'inventaire, pas une décision juridique contextuelle.

## Matrice de priorisation

```mermaid
quadrantChart
    title Risques NEXUS — Probabilité vs Impact
    x-axis Faible --> Élevé
    y-axis Faible --> Élevé
    quadrant-1 À surveiller
    quadrant-2 Risques majeurs
    quadrant-3 Acceptés / mitigés
    quadrant-4 À adresser en priorité
    R1-Scale SQLite: [0.5, 0.5]
    R4-FileLock réseau: [0.5, 0.9]
    R3-Symlink race locale: [0.2, 0.9]
    R5-Provider non coopératif: [0.4, 0.5]
    R16-Licence nouvelle dépendance: [0.3, 0.7]
```

## Procédure de mise à jour

Ce registre doit être mis à jour :

- après chaque intégration majeure ;
- lors de la clôture d'un risque avec preuve de mitigation ;
- lors de l'identification d'un nouveau risque en revue de code, sécurité ou architecture ;
- lorsqu'un document de roadmap ou de recovery change une frontière de support.
