/**
 * Workspaces, users, roles, invitations, sessions, 2FA (EX-02).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"platform", "contract", "validation", "archive"})
package de.erika.identity;

import org.springframework.modulith.ApplicationModule;
