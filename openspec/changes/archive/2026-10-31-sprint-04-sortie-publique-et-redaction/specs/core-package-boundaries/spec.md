# Spec Delta

## Purpose

Définit les dépendances autorisées entre packages du cœur, afin que les briques transverses restent des feuilles du graphe.

## ADDED Requirements

### Requirement: La politique de sortie ne dépend pas du package security
La politique d'exposition des contextes SHALL résider hors du package `com.nexus.security`, et ce package SHALL NOT importer de type du package `com.nexus.context`.

#### Scenario: Contrôle des imports
- **WHEN** on recherche `import com.nexus.context` dans `core/src/main/java/com/nexus/security/`
- **THEN** aucune occurrence n'est trouvée
