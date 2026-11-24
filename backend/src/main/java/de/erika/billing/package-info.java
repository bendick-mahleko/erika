/**
 * Stripe subscriptions, trial and entitlements, ERIKA's own e-invoices (EX-03, ADR 0033).
 *
 * <p>Owns {@code billing_account}, {@code billing_invoice} and {@code stripe_event}. Uses the
 * published interfaces {@code creator.SystemInvoices} (ERIKA's invoices, build plan gap 3),
 * {@code identity.WorkspaceProfiles} and {@code identity.WorkspaceContacts} (buyer data and
 * owners) and {@code intake.IntakeMailboxes} (a copy into the customer's inbox), and listens to
 * {@code identity.WorkspaceCreated} / {@code WorkspaceClosed} and {@code creator.InvoiceIssued}.
 */
@ApplicationModule(
        displayName = "Billing",
        allowedDependencies = {"platform", "contract", "identity", "creator", "intake"})
package de.erika.billing;

import org.springframework.modulith.ApplicationModule;
