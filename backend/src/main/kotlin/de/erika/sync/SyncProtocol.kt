package de.erika.sync

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Sync protocol versions the server accepts (ADR 0038). The server supports the current and
 * the previous version, so users whose app update is still in app-store review keep working.
 * A protocol change raises [CURRENT]; [MINIMUM] follows one release later.
 */
object SyncProtocol {
    const val CURRENT = 1
    const val MINIMUM = 1
}

/** App versions for the update check (`GET /mobile/status`). */
@ConfigurationProperties("erika.mobile")
data class MobileProperties(
    /** Below this version the app must update before it can sync. */
    val minAppVersion: String = "0.1.0",
    /** Below this version the app asks the user to update, but keeps working. */
    val recommendedAppVersion: String = "0.1.0",
)

/** A `major.minor.patch` app version; anything after `-` or `+` is ignored. */
internal data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        private val pattern = Regex("""^(\d{1,4})\.(\d{1,4})(?:\.(\d{1,6}))?(?:[-+].*)?$""")

        fun parse(text: String?): AppVersion? {
            val match = pattern.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
        }
    }
}

internal enum class UpdateAdvice { NONE, RECOMMENDED, REQUIRED }

internal fun updateAdvice(
    appVersion: String?,
    props: MobileProperties,
): UpdateAdvice {
    val app = AppVersion.parse(appVersion) ?: return UpdateAdvice.NONE
    val min = requireNotNull(AppVersion.parse(props.minAppVersion)) { "erika.mobile.min-app-version is not a version" }
    val recommended =
        requireNotNull(AppVersion.parse(props.recommendedAppVersion)) { "erika.mobile.recommended-app-version is not a version" }
    return when {
        app < min -> UpdateAdvice.REQUIRED
        app < recommended -> UpdateAdvice.RECOMMENDED
        else -> UpdateAdvice.NONE
    }
}
