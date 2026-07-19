---
status: accepted
date: 2026-07-19
---

# ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in

## Contexte et problème

NEXUS est destiné à analyser du code source, des instructions, de la documentation, des historiques Git et potentiellement d'autres données sensibles appartenant à des projets privés. Le moteur peut être utilisé dans des environnements professionnels où l'envoi implicite de contenu vers un service externe est interdit ou indésirable.

Certaines capacités futures pourraient cependant bénéficier de services distants : embeddings, index de code hébergé, repository Git distant, registre de skills ou fournisseurs cloud. Il faut donc définir une politique de sécurité claire avant d'introduire ces intégrations.

La question est : **le fonctionnement nominal de NEXUS doit-il dépendre de services externes, ou le moteur doit-il fonctionner localement par défaut avec toute sortie réseau explicitement activée ?**

## Facteurs de décision

- confidentialité du code source ;
- possibilité de traiter des repositories privés ;
- fonctionnement hors ligne pour le MVP ;
- maîtrise des coûts et crédits IA ;
- absence de dépendance obligatoire à un fournisseur ;
- besoin futur d'intégrations cloud ;
- traçabilité de la provenance et des échanges ;
- prévention de l'indexation de secrets et contenus générés.

## Options envisagées

- utiliser par défaut des services cloud pour l'indexation et la recherche ;
- utiliser un mode hybride avec appels externes automatiques lorsque disponibles ;
- adopter un fonctionnement local-first avec intégrations externes explicitement opt-in.

## Décision retenue

**Option retenue : adopter un fonctionnement local-first avec intégrations externes explicitement opt-in.**

Le chemin critique du MVP doit fonctionner sans :

- LLM distant ;
- fournisseur d'embeddings ;
- base vectorielle distante ;
- index de code hébergé ;
- GitHub ou GitLab comme source obligatoire ;
- registre de skills externe.

Toute intégration externe future doit être :

- explicitement activée ;
- identifiable dans la configuration ;
- observable dans les journaux ou métadonnées pertinentes ;
- désactivable sans empêcher le fonctionnement du cœur ;
- documentée quant aux données susceptibles de quitter la machine.

L'indexation locale doit respecter :

- `.gitignore` lorsque pertinent ;
- `.nexusignore` pour les exclusions propres à NEXUS ;
- des exclusions intégrées pour les contenus générés courants ;
- des exclusions de sécurité pour les secrets manifestes tels que `.env`, clés privées et certificats sensibles.

### Conséquences positives

- NEXUS peut être utilisé sur du code privé sans transfert réseau implicite ;
- le moteur reste utilisable hors ligne ;
- la dépendance à un fournisseur commercial est évitée ;
- les coûts externes restent sous contrôle de l'utilisateur ;
- les intégrations futures peuvent être évaluées séparément.

### Conséquences négatives et compromis acceptés

- certaines capacités sémantiques ou de recherche distribuée ne sont pas disponibles par défaut ;
- l'utilisateur doit configurer explicitement les intégrations externes ;
- le stockage local doit être dimensionné et sécurisé ;
- les règles d'exclusion demandent une maintenance continue.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| Indexation accidentelle d'un secret | Élevé | `.nexusignore`, exclusions intégrées, tests de sécurité et filtrage avant persistance |
| Envoi implicite de code à un provider externe | Critique | Aucun appel réseau dans le chemin local ; activation explicite par configuration |
| Faux sentiment de sécurité lié aux seules extensions de fichiers | Élevé | Combiner règles de chemin, extensions et mécanismes de détection complémentaires lorsque justifié |
| Index local laissé sans protection adéquate | Moyen à élevé | Documenter l'emplacement des données et éviter les copies cloud implicites |
| Une intégration devient de fait obligatoire | Élevé | Tests garantissant qu'un flux local complet reste disponible |

### Confirmation

La décision est respectée si :

- le MVP complet fonctionne après résolution initiale des dépendances sans service externe ;
- aucune clé API n'est nécessaire pour indexer, rechercher et construire un contexte ;
- `.nexusignore` est pris en compte ;
- les secrets et dossiers générés courants sont exclus ;
- toute fonctionnalité externe expose clairement son activation et sa provenance.

## Analyse détaillée des options

### Utiliser par défaut des services cloud

**Avantages :**

- accès rapide à des capacités puissantes ;
- moins de stockage ou calcul local ;
- possibilité d'utiliser immédiatement embeddings ou index hébergés.

**Inconvénients :**

- risque de confidentialité ;
- coût récurrent ;
- dépendance à la disponibilité réseau ;
- couplage à des fournisseurs ;
- incompatibilité potentielle avec des politiques d'entreprise.

### Utiliser un mode hybride avec appels externes automatiques

**Avantages :**

- expérience potentiellement transparente ;
- amélioration automatique lorsqu'un service est disponible.

**Inconvénients :**

- manque de prévisibilité sur les données envoyées ;
- comportement plus difficile à auditer ;
- surprise possible sur les coûts et la confidentialité.

### Adopter un fonctionnement local-first et opt-in

**Avantages :**

- comportement sûr et prévisible par défaut ;
- compatible avec les projets privés ;
- indépendance des fournisseurs ;
- évolutivité vers le cloud sans l'imposer.

**Inconvénients :**

- certaines fonctionnalités avancées demandent une configuration supplémentaire ;
- davantage de responsabilités locales pour le stockage et l'indexation.

## Impacts sur l'architecture

```text
Repository local
      │
      ▼
Scanner + Ignore Resolver
      │
      ▼
Index local SQLite / Lucene
      │
      ▼
NEXUS Core
      │
      ├── chemin local par défaut
      │
      └── providers externes optionnels
             │
             └── activation explicite
```

Les contrats de providers doivent pouvoir signaler leur caractère local ou externe et leur provenance.

## Conditions de réexamen

La décision peut être complétée, mais le principe local-first ne doit être abandonné que si la mission du projet change explicitement.

Une intégration externe spécifique peut faire l'objet d'un ADR séparé si elle implique :

- transfert de code ;
- données personnelles ;
- coût significatif ;
- contrainte réglementaire ;
- dépendance structurante.

## Décisions liées

- ADR-0001 — Positionner NEXUS comme moteur d'intelligence de contexte indépendant des modèles.
- ADR-0006 — Utiliser SQLite comme source de vérité structurelle locale.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0014 — Rendre la recherche sémantique et les embeddings optionnels.
