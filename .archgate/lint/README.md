# Règles de linter

Ce dossier accueille les règles propres à un linter qui font respecter les ADR au niveau du linter.

## Convention

Un fichier de plugin par outil :

- `oxlint.js` — règles oxlint personnalisées (plugin JavaScript) ;
- `eslint.js` — règles ESLint personnalisées ;
- `biome.js` — règles Biome personnalisées.

## Usage avec oxlint

1. Créer `.archgate/lint/oxlint.js`, qui exporte les règles du plugin.
2. Le référencer dans la configuration oxlint :

```json
{
  "plugins": [".archgate/lint/oxlint.js"]
}
```

## Pourquoi ici ?

Archgate fait de `.archgate/lint/` l'emplacement des règles de linter qui complètent les contrôles des ADR. Les artefacts de gouvernance restent ensemble : les règles de linter dans `lint/`, les ADR dans le registre `docs/architecture/adr/` (GEN-0057). NEXUS n'a aujourd'hui aucune règle de linter : le dossier ne contient que ce fichier.
