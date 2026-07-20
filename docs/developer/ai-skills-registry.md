# AI Skills Registry dans NEXUS

## Objectif

L'Itération 14 permet à NEXUS de découvrir des skills provenant d'un snapshot local de AI Skills Registry, sans introduire de dépendance réseau pendant la construction du contexte.

Le comportement reste local-first et optionnel : en l'absence de snapshot, NEXUS continue à fonctionner uniquement avec les skills présents dans le projet.

## Emplacement du snapshot

NEXUS recherche les définitions du registre sous :

```text
.nexus/registry/skills/**/SKILL.md
```

Le contenu de `.nexus/registry` est considéré comme un cache local et n'est pas versionné avec le repository NEXUS.

## Pipeline

```text
skills locaux du projet
        +
snapshot AI Skills Registry
        |
        v
SkillDiscoveryService
        |
        v
déduplication par nom et priorité
        |
        v
SkillSelector
        |
        v
SkillLoader
        |
        v
SkillContextSelector
```

`AiSkillsRegistryProvider` lit uniquement le frontmatter YAML pendant la découverte. Le corps complet d'un `SKILL.md` n'est chargé qu'après sélection par `SkillLoader`.

## Priorités

Les priorités initiales sont :

- skill local du projet : `80` ;
- skill issu du registre : `60`.

Lorsque deux skills portent le même nom, `SkillDiscoveryService` conserve donc la définition locale du projet.

Cette règle permet à un projet de spécialiser ou remplacer localement un skill partagé sans modifier le registre central.

## Absence du registre

Si `.nexus/registry/skills` n'existe pas, le provider retourne un catalogue vide sans erreur.

Aucun accès réseau et aucune opération Git ne sont déclenchés automatiquement par NEXUS pendant une requête de contexte.

## Validation

L'Itération 14 vérifie explicitement que :

- les métadonnées du registre sont découvertes sans charger le corps complet du skill ;
- le corps complet est chargé uniquement après sélection ;
- un skill local de même nom conserve la priorité ;
- le build complet du cœur reste vert ;
- le self-smoke historique reste vert sans snapshot de registre.

Commande de validation dédiée :

```powershell
.\scripts\validate-iteration-14.ps1
```

## Limites actuelles

Le premier incrément ne synchronise pas lui-même le dépôt externe et n'exploite pas encore tous les champs propres au registre comme `version`, `status`, `category` ou `tags` pour le ranking.

Ces enrichissements pourront être ajoutés derrière le même contrat sans modifier le pipeline de sélection des skills.
