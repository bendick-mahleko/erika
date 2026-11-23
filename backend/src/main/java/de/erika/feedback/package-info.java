/**
 * Feedback from users to the ERIKA team during the closed beta (ADR 0030).
 *
 * <p>Owns the {@code feedback} table. Uses identity's published {@code WorkspaceContacts}
 * for the sender's name and address.
 */
@ApplicationModule(displayName = "Feedback", allowedDependencies = {"platform", "contract", "identity"})
package de.erika.feedback;

import org.springframework.modulith.ApplicationModule;
