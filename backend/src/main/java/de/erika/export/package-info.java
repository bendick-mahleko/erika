/**
 * Period export (ZIP + index + DATEV CSV) and full account export (EX-01, EX-04, ADR 0032).
 *
 * <p>Owns {@code data_export} and {@code datev_settings}. Uses the published interfaces
 * {@code archive.Archive} (originals, hash-checked), {@code documents.DocumentExports} (the
 * documents of a period) and {@code identity.WorkspaceContacts} (the requester's email); the full
 * export collects every module's {@code platform.export.AccountDataSection}.
 */
@ApplicationModule(
        displayName = "Export",
        allowedDependencies = {"platform", "contract", "archive", "documents", "identity"})
package de.erika.export;

import org.springframework.modulith.ApplicationModule;
