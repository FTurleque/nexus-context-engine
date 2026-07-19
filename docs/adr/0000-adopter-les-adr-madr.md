---
status: accepted
date: 2026-07-19
---

# ADR-0000 — Adopter MADR comme format et définir la gouvernance des ADR

## Contexte et problème

NEXUS est conçu comme un projet d'architecture évolutive : le MVP est volontairement réduit, tandis que de nombreuses briques sont prévues à plus long terme, notamment Lucene, SCIP, JDT LS, Tree-sitter, MCP, Agent Skills et des adaptateurs Copilot ou Claude.

Les décisions prises aujourd'hui doivent rester compréhensibles lorsque l'architecture évoluera. Une documentation d'architecture synthétique décrit l'état courant, mais elle ne conserve pas suffisamment le contexte historique, les alternatives envisagées ni les compromis acceptés.

Le projet a donc besoin d'un mécanisme versionné avec le code permettant de répondre durablement à la question : **pourquoi cette décision a-t-elle été prise à ce moment du projet, et dans quelles conditions pourrait-elle être remplacée ?**

## Facteurs de décision

- conserver la justification des décisions importantes au même endroit que le code ;
- disposer de documents lisibles directement sur GitHub ;
- utiliser un format Markdown simple, diffable et versionnable ;
- documenter explicitement les alternatives retenues et rejetées ;
- éviter une documentation d'architecture « en vrac » ;
- permettre aux nouveaux contributeurs de reconstruire le raisonnement architectural ;
- permettre la supersession d'une décision sans réécrire l'histoire ;
- rester compatible avec des outils ADR existants sans rendre un outil obligatoire.

## Options envisagées

- ne conserver que `docs/architecture.md` et l'historique Git ;
- utiliser le modèle historique minimal de Michael Nygard ;
- utiliser MADR 4 dans sa forme standard ;
- utiliser un format MADR 4 adapté en français et enrichi pour NEXUS.

## Décision retenue

**Option retenue : utiliser un format MADR 4 adapté en français et enrichi pour NEXUS.**

Chaque décision architecturale significative est enregistrée dans un fichier Markdown distinct sous `docs/adr/`.

Le format NEXUS reprend la structure essentielle de MADR :

- contexte et problème ;
- facteurs de décision ;
- options envisagées ;
- décision retenue ;
- conséquences ;
- confirmation ;
- analyse détaillée des options ;
- informations complémentaires.

NEXUS ajoute systématiquement, lorsque pertinent :

- risques et mesures de maîtrise ;
- impacts sur l'architecture ;
- conditions de réexamen ;
- décisions liées.

Les métadonnées sont placées dans un front matter YAML avec au minimum `status` et `date`.

Les ADR acceptés sont considérés comme des enregistrements historiques. Une décision substantiellement modifiée doit être remplacée par un nouvel ADR ; l'ancien devient `superseded` et référence son remplaçant.

Les options rejetées sont documentées dans l'ADR concerné et non dans un document transversal séparé.

### Conséquences positives

- l'historique des décisions devient explicite et consultable sans dépendre de la mémoire des contributeurs ;
- les alternatives et compromis restent associés à la décision qu'ils expliquent ;
- les décisions peuvent être relues et challengées indépendamment du code ;
- l'architecture courante peut rester concise tandis que les ADR portent le détail historique ;
- les changements de direction futurs peuvent être tracés par supersession ;
- le format reste exploitable par des humains, Git et des outils automatisés.

### Conséquences négatives et compromis acceptés

- les ADR demandent une discipline documentaire continue ;
- certaines informations pourront apparaître à la fois dans `architecture.md` et dans un ADR ;
- un registre mal maintenu deviendrait rapidement obsolète ;
- le niveau de détail retenu pour NEXUS est supérieur au format ADR minimal et représente donc un coût rédactionnel assumé.

### Risques et mesures de maîtrise

| Risque | Impact | Mesure de maîtrise |
|---|---|---|
| ADR trop nombreux pour des décisions mineures | Moyen | Réserver les ADR aux décisions ayant un impact durable sur architecture, dépendances, données, sécurité, interopérabilité ou exploitation |
| Divergence entre ADR et architecture actuelle | Élevé | Utiliser `docs/architecture.md` comme vue actuelle et les ADR comme historique ; créer une supersession lorsqu'une décision change |
| Modification rétroactive de l'histoire | Élevé | Interdire les réécritures substantielles d'un ADR accepté |
| Format trop lourd pour les futures décisions simples | Faible | Le modèle est extensible : les sections non pertinentes peuvent rester courtes, mais contexte, options, décision et conséquences restent obligatoires |

### Confirmation

Le respect de cette décision est confirmé lorsque :

- `docs/adr/README.md` contient l'index à jour ;
- chaque décision architecturale significative possède son ADR ;
- les nouveaux ADR utilisent la numérotation séquentielle ;
- toute décision remplacée utilise le mécanisme de supersession ;
- `docs/architecture.md` renvoie vers le registre ADR.

## Analyse détaillée des options

### Ne conserver que `docs/architecture.md` et l'historique Git

**Avantages :**

- aucun artefact supplémentaire ;
- maintenance documentaire minimale.

**Inconvénients :**

- l'historique Git indique ce qui a changé, pas nécessairement pourquoi ;
- les alternatives étudiées sont perdues ;
- les compromis deviennent difficiles à reconstruire ;
- les décisions successives risquent d'être présentées comme un état final sans mémoire du contexte initial.

### Utiliser le modèle historique minimal de Michael Nygard

**Avantages :**

- format extrêmement simple ;
- faible coût de rédaction ;
- structure classique : statut, contexte, décision, conséquences.

**Inconvénients :**

- moins adapté au niveau de détail souhaité pour NEXUS ;
- les facteurs de décision et l'analyse comparative des options nécessiteraient des conventions supplémentaires.

### Utiliser MADR 4 dans sa forme standard

**Avantages :**

- format structuré et reconnu ;
- options et facteurs de décision explicites ;
- section de confirmation ;
- bonne lisibilité en Markdown.

**Inconvénients :**

- la structure standard ne formalise pas systématiquement les risques, impacts et critères de réexamen que NEXUS souhaite conserver.

### Utiliser MADR 4 adapté en français et enrichi pour NEXUS

**Avantages :**

- conserve la structure éprouvée de MADR ;
- documentation cohérente avec la langue du projet ;
- permet une analyse complète des décisions ;
- intègre les besoins spécifiques de suivi des risques et d'évolution de NEXUS.

**Inconvénients :**

- adaptation locale à maintenir ;
- format légèrement plus long que MADR minimal.

## Impacts sur l'architecture

Aucun impact d'exécution. Cette décision affecte la gouvernance documentaire :

```text
docs/architecture.md
        │
        │ décrit l'état actuel
        ▼
docs/adr/README.md
        │
        ├── ADR-0000
        ├── ADR-0001
        └── ...
             │
             └── expliquent l'historique et les raisons
```

## Conditions de réexamen

Cette décision doit être réévaluée si :

- le volume d'ADR rend le registre difficile à maintenir ou à rechercher ;
- un outil de documentation impose un format incompatible ;
- le projet adopte une gouvernance architecturale multi-repositories nécessitant un registre fédéré.

Le principe de conserver les décisions avec leur contexte historique reste toutefois à préserver, même si le format évolue.

## Références

- MADR : https://adr.github.io/madr/
- Modèles ADR : https://adr.github.io/adr-templates/
- MADR sur GitHub : https://github.com/adr/madr
