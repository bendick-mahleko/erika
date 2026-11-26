/**
 * Customers, articles, drafts, numbering, credit notes, sending (CR-01..07, ADR 0031).
 *
 * <p>Owns its own tables. Talks to other feature modules only through published
 * interfaces: {@code archive.Archive}, {@code documents.Documents} (outgoing documents and
 * their statuses), {@code identity.WorkspaceProfiles} (the seller) and
 * {@code validation.InvoiceValidator} (worker only).
 *
 * <p>MARA (Stage 4, ADR 0038): customers and articles are syncable records; this module implements
 * {@code sync.SyncEntity} and reports changes to {@code sync.ChangeFeed}.
 */
@ApplicationModule(
        displayName = "Creator",
        allowedDependencies = {"platform", "contract", "validation", "archive", "documents", "identity", "sync"})
package de.erika.creator;

import org.springframework.modulith.ApplicationModule;
