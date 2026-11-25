package de.erika.reportinvoicing

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Invoice preview from signed reports and the hand-over to the creator module, which alone issues invoices (MARA Stage 8, ADR 0036).
 *
 * Module declaration (Kotlin has no package-info): see ADR 0036.
 */
@PackageInfo
@ApplicationModule(displayName = "MARA: invoicing from reports")
internal class ModuleInfo
