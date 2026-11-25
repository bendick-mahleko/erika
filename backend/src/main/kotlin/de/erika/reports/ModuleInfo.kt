package de.erika.reports

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Report versions with time entries, materials, Aufmaß, checklist answers and signatures; signed reports never change (MARA Stages 5 and 7, M-BR-01). The calculation rules mirror mara-rules/ and must pass its shared test vectors.
 *
 * Module declaration (Kotlin has no package-info): see ADR 0036.
 */
@PackageInfo
@ApplicationModule(displayName = "MARA: job reports")
internal class ModuleInfo
