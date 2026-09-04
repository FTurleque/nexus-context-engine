# Native context discovery limits

NEXUS applique un budget de travail cumulatif à la découverte de contexte natif **avant** la sélection de tokens. La même instance est partagée par les providers d'instructions, Agent Skills, AI Skills Registry, contexte Git local et détection de customisations.

## Defaults

| Dimension | Variable d'environnement | Défaut | Maximum dur |
|---|---|---:|---:|
| Entrées visitées | `NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES` | 100000 | 1000000 |
| Ressources candidates | `NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES` | 5000 | 100000 |
| Octets cumulés | `NEXUS_CONTEXT_DISCOVERY_MAX_BYTES` | 33554432 | 536870912 |
| Durée globale (ms) | `NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS` | 15000 | 120000 |

Toute valeur doit être strictement positive et sous le maximum dur. Une configuration invalide échoue fermé au démarrage d'une construction de contexte.

## Accounting

Le budget est consommé avant le travail coûteux lorsque possible :

- chaque entrée filesystem/Git visitée charge le compteur de visites ;
- chaque ressource native candidate charge le compteur de candidats ;
- les lectures de contenus et les diffs rendus chargent le compteur d'octets ;
- tous les providers partagent la même deadline.

Un dépassement provoque `ContextDiscoveryLimitExceededException`. NEXUS ne retourne pas silencieusement une découverte partielle dont la complétude serait ambiguë.

## Git local

Le provider Git ajoute des caps structurels sur les commits, chemins modifiés et historique. Le diff visible est limité à 6000 caractères par zone et utilise un sink de sortie à capacité fixe avant conversion UTF-8 ; un patch massif n'est donc pas d'abord accumulé dans un buffer extensible complet.

`LocalGitContextSourceProviderTest` qualifie à la fois :

- un diff cible massif tronqué de manière déterministe ;
- la capacité fixe du sink, qui ne retient jamais plus d'octets que sa capacité configurée.

## Benchmark hermétique

`NativeContextDiscoveryBudgetBenchmarkTest` fait partie de `.github/workflows/scale-benchmark.yml`.

Le scénario crée 1 000 `SKILL.md` dans un AI Skills Registry synthétique et exécute le vrai `AiSkillsRegistryProvider` au seuil exact :

```text
skills              1000
visited entries     2001
candidate resources 1000
```

Le benchmark vérifie :

- déterminisme de l'ordre de découverte ;
- comptage exact des visites/candidats ;
- octets cumulés sous budget ;
- durée <= 10 s sur le runner de qualification.

Le rapport est conservé sous :

```text
target/native-discovery-scale-benchmark.json
```

Ce benchmark complète les tests de frontière exacte/N+1 de `ContextDiscoveryLimitsTest` et matérialise le coût réel d'une découverte native filesystem.
