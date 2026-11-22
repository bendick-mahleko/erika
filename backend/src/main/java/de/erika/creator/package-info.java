/**
 * Customers, articles, drafts, numbering, credit notes, sending (CR-01..09).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Creator", allowedDependencies = {"platform", "contract", "validation", "archive"})
package de.erika.creator;

import org.springframework.modulith.ApplicationModule;
