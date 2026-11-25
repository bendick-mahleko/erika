package de.erika.media

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Resumable photo uploads (tus), virus scan, hash check and thumbnails (MARA Stage 6, ADR 0040).
 *
 * Module declaration (Kotlin has no package-info): see ADR 0036.
 */
@PackageInfo
@ApplicationModule(displayName = "MARA: photos and media")
internal class ModuleInfo
