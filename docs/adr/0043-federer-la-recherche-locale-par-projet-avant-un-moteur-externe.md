---
status: accepted
date: 2026-07-21
---

# ADR-0043 — Fédérer la recherche locale par projet avant d'introduire un moteur externe

## Contexte et problème

NEXUS doit, à partir de l'Itération 16, dépasser le cas d'un unique repository local. Le registre de projets, SQLite et Lucene savent déjà isoler plusieurs projets par `projectId`, mais l'orchestration de recherche actuelle prend un seul `ProjectDescriptor` à la fois.

La roadmap mentionne Zoekt, OpenGrok, des index distants, un cache partagé et la recherche fédérée. Introduire immédiatement un nouveau moteur ajouterait toutefois une dépendance opérationnelle avant d'avoir démontré que Lucene et l'architecture locale actuelle sont insuffisants.

La question est : **comment ouvrir la recherche multi-repository tout en conservant Lucene par défaut et en obtenant les mesures nécessaires avant toute intégration externe ?**

## Facteurs de décision

- conserver SQLite comme stockage canonique ;
- conserver Lucene comme index local dérivé et reconstructible ;
- préserver le `SearchService`, le ranking et le `ContextBuilder` tant qu'aucune mesure ne justifie leur modification ;
- préserver explicitement la provenance `projectId` ;
- permettre une sélection explicite de plusieurs projets ;
- éviter toute dépendance réseau pour une recherche locale ;
- garder les adaptateurs REST et MCP indépendants du cœur ;
- mesurer la qualité et les performances avant d'évaluer Zoekt ou OpenGrok.

## Options envisagées

- remplacer Lucene par Zoekt ou OpenGrok dès le début de l'Itération 16 ;
- construire un index Lucene global regroupant tous les projets ;
- modifier `SearchService` pour lui faire gérer directement plusieurs projets ;
- fédérer plusieurs recherches projet-locales au-dessus du `SearchService` existant.

## Décision retenue

**Option retenue : fédérer plusieurs recherches projet-locales au-dessus du `SearchService` existant, avec une portée de projets explicite et une provenance conservée.**

Le premier incrément introduit `FederatedSearchService` :

```text
Project A ─┐
Project B ─┼─> SearchService par projet ─> RankedCandidate
Project C ─┘                              │
                                           ▼
                                FederatedSearchService
                                           │
                                           ▼
                                FederatedSearchHit
                                - ProjectDescriptor
                                - RankedCandidate
```

Chaque projet continue d'utiliser :

- son index Lucene isolé ;
- ses données SQLite identifiées par `projectId` ;
- les stratégies de recherche existantes ;
- le ranking déterministe existant.

La fédération :

- accepte uniquement une liste explicite de projets ;
- déduplique la portée par `projectId` ;
- exécute la recherche indépendamment pour chaque projet ;
- ne déduplique pas deux résultats provenant de projets distincts ;
- trie les résultats globaux de manière déterministe ;
- conserve le `ProjectDescriptor` avec chaque résultat.

`NexusApplication` expose une opération `searchAcrossProjects(...)` afin que les adaptateurs puissent ultérieurement proposer le même comportement sans réimplémenter la fédération.

Aucune recherche globale implicite sur tous les projets n'est introduite dans ce premier incrément.

Le `ContextBuilder` reste mono-projet. La construction d'un contexte multi-projet doit faire l'objet d'une décision séparée, car les instructions natives, skills et données Git sont projet-locales et doivent être arbitrées sous un budget de tokens unique.

## Conséquences positives

- le multi-repository devient possible sans nouveau moteur ;
- la provenance est explicite et non inférée depuis un chemin ;
- les index restent isolés et reconstructibles par projet ;
- aucune dépendance réseau ou serveur n'est ajoutée ;
- REST et MCP pourront réutiliser la même façade applicative ;
- Zoekt et OpenGrok restent des options mesurées, non des dépendances anticipées.

## Conséquences négatives et compromis acceptés

- une recherche fédérée exécute actuellement une recherche par projet ;
- le coût total croît donc avec le nombre de projets sélectionnés ;
- les signaux lexicaux Lucene sont normalisés à l'intérieur de chaque projet avant le ranking, ce qui rend la comparabilité inter-projets à mesurer explicitement ;
- aucun parallélisme n'est introduit avant d'avoir mesuré le besoin ;
- le contexte multi-projet n'est pas couvert par ce premier incrément.

## Baseline obligatoire avant moteur externe

L'Itération 16 doit mesurer au minimum :

- nombre de repositories ;
- nombre de fichiers ;
- nombre de symboles ;
- nombre de relations ;
- taille cumulée et taille par projet des index Lucene ;
- temps d'indexation complète ;
- temps d'indexation incrémentale ;
- latence de recherche projet-local et fédérée ;
- temps de construction du contexte ;
- `precision@3` et `recall@3` sur un corpus incluant plusieurs projets ;
- consommation mémoire lorsque la mesure est reproductible.

Les mesures doivent distinguer au minimum le nombre de projets et les volumes cumulés. Les latences doivent être relevées après échauffement et avec plusieurs répétitions afin d'éviter de conclure sur une seule exécution.

## Critère d'évaluation d'un moteur externe

Zoekt, OpenGrok ou un autre backend externe ne doivent être évalués que si une baseline reproductible montre au moins une limite significative de l'architecture locale, par exemple :

- latence de recherche fédérée incompatible avec l'usage cible ;
- temps ou coût mémoire d'indexation non acceptable ;
- taille d'index ou temps de reconstruction problématique ;
- dégradation mesurable de `precision@3` ou `recall@3` ;
- coût de la recherche structurelle SQLite dominant à fort volume de symboles ;
- besoin réel d'index distants impossibles à satisfaire proprement avec les index locaux isolés.

Un moteur externe devra rester derrière un port ou une stratégie NEXUS et ne devra pas devenir requis pour les recherches locales.

## Confirmation

La décision est respectée si :

- un appel fédéré recherche plusieurs projets explicitement sélectionnés ;
- chaque résultat conserve son projet d'origine ;
- une recherche locale continue de fonctionner sans réseau ni moteur externe ;
- `SearchService`, le ranking et `DefaultContextBuilder` ne sont pas modifiés pour cet incrément ;
- les résultats de projets différents ne sont pas dédupliqués sur leur seul chemin relatif ;
- aucune intégration Zoekt ou OpenGrok n'est ajoutée sans mesures justificatives.

## Conditions de réexamen

Réexaminer cette décision si les mesures de passage à l'échelle montrent que l'exécution d'une recherche par projet, la recherche structurelle SQLite ou la reconstruction des index Lucene ne satisfont plus les besoins opérationnels de NEXUS.

## Décisions liées

- ADR-0005 — Adopter un fonctionnement local-first et des intégrations externes opt-in.
- ADR-0007 — Utiliser Apache Lucene comme index de recherche local.
- ADR-0010 — Adopter un ranking hybride, déterministe et explicable.
- ADR-0013 — Construire un `ContextBundle` sous budget de tokens explicable.
- ADR-0022 — Traiter Lucene comme un index dérivé et reconstructible de SQLite.
- ADR-0024 — Combiner Lucene et SQLite pour la recherche de candidats.
