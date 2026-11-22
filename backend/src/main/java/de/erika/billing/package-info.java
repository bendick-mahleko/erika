/**
 * Stripe plans, trials, webhooks, entitlements (EX-03).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces or domain events (Software Design §3).
 */
@ApplicationModule(displayName = "Billing", allowedDependencies = {"platform", "contract", "validation", "archive"})
package de.erika.billing;

import org.springframework.modulith.ApplicationModule;
