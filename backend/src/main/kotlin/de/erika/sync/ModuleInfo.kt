package de.erika.sync

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Offline sync for the technician app: change feed, push/pull, idempotency, field versions, conflicts and protocol versions (MARA Stage 4, ADR 0038).
 *
 * Module declaration (Kotlin has no package-info): see ADR 0036.
 */
@PackageInfo
@ApplicationModule(displayName = "MARA: sync")
internal class ModuleInfo
