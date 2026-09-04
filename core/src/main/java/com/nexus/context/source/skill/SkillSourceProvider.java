package com.nexus.context.source.skill;

import java.io.IOException;

/**
 * Port de découverte légère de catalogues Agent Skills.
 *
 * <p>Un provider ne doit pas charger le corps complet des SKILL.md pendant la
 * découverte. Il retourne uniquement les métadonnées nécessaires à la
 * sélection.</p>
 */
public interface SkillSourceProvider {

    String id();

    SkillProviderResult discover(SkillSourceQuery query) throws IOException;
}
