/**
 * Period export (ZIP + DATEV CSV) and full account export (EX-01, EX-04).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Export", allowedDependencies = {"platform", "contract", "validation", "archive"})
package de.erika.export;

import org.springframework.modulith.ApplicationModule;
