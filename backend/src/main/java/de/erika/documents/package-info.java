/**
 * Document records, statuses, tags, notes, search (AR-03..06).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Documents", allowedDependencies = {"platform", "contract", "validation", "archive", "identity"})
package de.erika.documents;

import org.springframework.modulith.ApplicationModule;
