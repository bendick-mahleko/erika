package de.erika.workorders

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Sites, work orders (Aufträge), assignment, calendar, checklist templates and hourly rates per staff level (MARA Stage 2). Called "work order", not "job": in ERIKA, a job is a background job (platform.jobs).
 *
 * Module declaration (Kotlin has no package-info): see ADR 0036.
 */
@PackageInfo
@ApplicationModule(displayName = "MARA: work orders")
internal class ModuleInfo
