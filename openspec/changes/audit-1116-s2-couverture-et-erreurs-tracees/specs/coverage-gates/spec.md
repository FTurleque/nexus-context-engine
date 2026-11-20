# Spec Delta

## ADDED Requirements

### Requirement: Les paquets critiques du core ont un plancher par paquet
Les paquets `com.nexus.persistence.sqlite`, `com.nexus.search.lucene`, `com.nexus.context`, `com.nexus.cli` et `com.nexus.application` du module `core` SHALL porter chacun une règle JaCoCo de niveau paquet, en lignes et en branches, qui fait échouer `./mvnw clean install` sous son plancher.

#### Scenario: Régression concentrée sur un paquet critique
- **GIVEN** une modification qui fait passer la couverture de lignes de `com.nexus.persistence.sqlite` sous son plancher sans faire passer le module `core` sous son plancher `BUNDLE`
- **WHEN** `./mvnw clean install` s'exécute
- **THEN** l'exécution JaCoCo du core échoue en nommant le paquet

#### Scenario: Plancher publié
- **WHEN** le contrôle des planchers compare les `pom.xml` à la page de couverture
- **THEN** chacun des cinq paquets a une ligne de seuil et une ligne de mesure de référence, et le contrôle passe

#### Scenario: Mesure courante sur les deux plateformes
- **WHEN** `./mvnw clean install` puis le contrôle des planchers s'exécutent sous Linux et sous Windows sur le HEAD
- **THEN** les cinq règles de paquet sont respectées et le contrôle passe sur chaque plateforme

### Requirement: La preuve qu'une règle de classe fait échouer le build est rejouée automatiquement
Un job CI planifié SHALL rejouer la régression d'une règle JaCoCo de niveau classe en retirant de l'exécution les tests qui couvrent la classe, et SHALL échouer si le build qui en résulte n'échoue pas sur cette règle en nommant la classe.

#### Scenario: Règle qui mord
- **GIVEN** les trois tests `rejectsReverseProxyHttps*` de `NexusRestExposureGuardTest` exclus de l'exécution
- **WHEN** le job construit le module REST avec ses contrôles JaCoCo
- **THEN** le build échoue sur la règle de classe de `NexusRestTransportPolicy`
- **AND** le job est vert

#### Scenario: Règle devenue inopérante
- **GIVEN** une configuration JaCoCo où la règle de classe de `NexusRestTransportPolicy` ne s'applique plus
- **WHEN** le job s'exécute avec les mêmes tests exclus
- **THEN** le job échoue en indiquant que la régression attendue n'a pas été détectée

## MODIFIED Requirements

### Requirement: La page de couverture publie la mesure de référence des planchers
La page de couverture SHALL publier, pour chaque cible qui porte un plancher, la mesure de référence et le commit où elle a été relevée. Chaque ligne SHALL donner la mesure Linux et la mesure Windows du reactor complet, relevées au même commit, en lignes et en branches, avec le nombre couvert et le total du build de ce commit.

#### Scenario: Plancher sans mesure publiée
- **WHEN** un plancher existe dans un `pom.xml` sans ligne de mesure correspondante dans la page
- **THEN** le contrôle documentaire échoue en nommant la cible

#### Scenario: Mesure relevée au commit cité
- **GIVEN** le commit cité par la page de couverture
- **WHEN** le reactor complet est construit à ce commit sous Linux et sous Windows
- **THEN** pour chaque ligne de la table, le nombre couvert et le total de lignes et de branches de chaque plateforme sont ceux du rapport JaCoCo de cette plateforme
