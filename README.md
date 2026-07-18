# NEXUS Context Engine

> Un moteur local, indépendant des modèles, dédié à l'intelligence de contexte pour les projets logiciels.

NEXUS construit un contexte minimal, pertinent, explicable et traçable pour les assistants et agents IA. NEXUS n'est pas un chatbot et ne réalise aucun routage vers un modèle particulier.

## Mission

À partir d'un repository logiciel local et d'une demande en langage naturel, NEXUS doit identifier et classer les fichiers et symboles les plus susceptibles d'être utiles, puis construire un `ContextBundle` respectant un budget de tokens configurable.

```text
Utilisateur / Agent / IDE
          │
          ▼
        Demande
          │
          ▼
        NEXUS
   indexation + recherche
   classement + explication
   budget + construction
          │
          ▼
     ContextBundle
          │
          ▼
      LLM / Agent IA
```

## Périmètre du MVP

Le périmètre du MVP est volontairement resserré :

- repositories locaux uniquement ;
- Java en premier langage pris en charge ;
- indexation structurelle basée sur un AST ;
- recherche lexicale et recherche orientée symboles ;
- classement déterministe et explicable ;
- budget de tokens configurable ;
- extraits de fichiers et de symboles plutôt qu'une injection systématique des fichiers complets ;
- fonctionnement local par défaut ;
- aucune dépendance obligatoire à un LLM ou à un fournisseur d'embeddings.

Sont explicitement reportés : les sources GitHub et GitLab, les intégrations IDE, le serveur MCP complet, les embeddings externes, les bases vectorielles, ainsi que les intégrations avec JARVIS, Alfred, Brainiac et AI Skills Registry.

## Orientation architecturale

Le repository démarre avec un seul module Maven organisé par responsabilités. Des modules Maven distincts ne seront créés que lorsqu'une séparation de runtime, de packaging ou de dépendances le justifiera réellement.

Les principaux points d'extension du cœur sont :

- `LanguageAnalyzer` ;
- `SearchStrategy` ;
- `ContextRanker` ;
- `TokenEstimator` ;
- `ContextBuilder`.

Consulter [docs/architecture.md](docs/architecture.md), [docs/mvp.md](docs/mvp.md) et [docs/roadmap.md](docs/roadmap.md).

## Socle technique

- Java 21 comme niveau de compilation du MVP ;
- Maven ;
- JavaParser pour le premier analyseur AST Java ;
- JUnit pour les tests automatisés.

Le cœur est volontairement développé en Java sans framework applicatif. Quarkus pourra être introduit ultérieurement au niveau de l'adaptateur API, sans coupler le moteur de contexte à un runtime particulier.

## État du projet

**Itération 0 — socle architectural et contrats d'indexation Java.**

Le repository contient uniquement les éléments nécessaires au démarrage de la première tranche verticale du MVP.

## Sécurité par défaut

NEXUS adopte une approche locale par défaut. Aucun contenu du repository ne doit quitter la machine sans activation explicite d'une intégration externe. Un fichier `.nexusignore` complète les mécanismes de type `.gitignore` afin d'exclure notamment les secrets, les fichiers sensibles et les contenus générés.

## Licence

Le choix de la licence reste volontairement ouvert tant que le repository n'est pas rendu public.
