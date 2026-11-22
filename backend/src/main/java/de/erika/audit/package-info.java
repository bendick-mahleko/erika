/**
 * Append-only, hash-chained audit log fed by domain events (NF-10).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = {"platform"})
package de.erika.audit;

import org.springframework.modulith.ApplicationModule;
