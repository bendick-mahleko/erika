/**
 * XSD + Schematron validation with versioned KoSIT artefact packs; rule texts (VW-03/04, BR-07).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Validation", allowedDependencies = {"platform"})
package de.erika.validation;

import org.springframework.modulith.ApplicationModule;
