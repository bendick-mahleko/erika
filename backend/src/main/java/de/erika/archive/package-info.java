/**
 * Write-once originals by SHA-256, retention, legal hold (AR-01/02, BR-02/03).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Archive", allowedDependencies = {"platform"})
package de.erika.archive;

import org.springframework.modulith.ApplicationModule;
