/**
 * Upload and e-mail intake, ZIP unpacking, format detection, duplicates (IN-01..06).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Intake", allowedDependencies = {"platform", "contract", "validation", "archive"})
package de.erika.intake;

import org.springframework.modulith.ApplicationModule;
