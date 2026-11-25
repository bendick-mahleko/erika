package de.erika.platform.features

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Product areas that are switched on per installation. Off by default, so unfinished areas
 * stay hidden in production until they are released.
 */
@ConfigurationProperties("erika.features")
data class FeatureProperties(
    /** MARA job reports: sites, staff levels, checklist templates, work orders (ADR 0036). */
    val mara: Boolean = false,
)
