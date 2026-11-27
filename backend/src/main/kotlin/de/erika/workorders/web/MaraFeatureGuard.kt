package de.erika.workorders.web

import de.erika.billing.MaraEntitlement
import de.erika.platform.features.FeatureProperties
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MARA's endpoints answer 404 `FEATURE_DISABLED` unless `erika.features.mara` is on (the switch
 * per installation). Where it is on, changes need the add-on (MARA Stage 10, ADR 0043): without a
 * trial or booking, 403 `MARA_NOT_BOOKED` or `MARA_READ_ONLY`; reading, exporting, signing in,
 * revoking devices and booking always work. A phone's push (`/mobile/sync/push`) is refused as a
 * whole; the phone keeps its outbox and can still pull.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class MaraFeatureGuard(
    private val features: FeatureProperties,
    private val entitlement: MaraEntitlement,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!features.mara) throw ErikaException("FEATURE_DISABLED", HttpStatus.NOT_FOUND, "Not available.")
        if (request.method in READ_METHODS) return true
        val path = request.requestURI
        if (ALWAYS_ALLOWED.any { path == it || path.startsWith("$it/") }) return true
        val principal = ErikaPrincipal.currentOrNull() ?: return true
        entitlement.requireWritable(principal.workspaceId)
        return true
    }

    companion object {
        private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")

        /** Changes that never need the add-on: booking it, signing in, locking out a lost phone, finishing uploads. */
        private val ALWAYS_ALLOWED =
            listOf(
                "/api/v1/billing/addons/mara",
                "/api/v1/mobile/auth",
                "/api/v1/mobile/devices",
                "/api/v1/mobile/uploads",
                "/api/v1/devices",
                "/api/v1/public/report-verifications",
            )

        val PATHS =
            listOf(
                "/api/v1/sites",
                "/api/v1/staff-levels",
                "/api/v1/staff",
                "/api/v1/checklist-templates",
                "/api/v1/work-orders",
                // MARA Stage 3: device sign-in, the device status and the owner's device list.
                "/api/v1/devices",
                "/api/v1/mobile/auth",
                "/api/v1/mobile/devices",
                // MARA Stage 6: photos and their uploads.
                "/api/v1/photos",
                "/api/v1/mobile/uploads",
                // MARA Stage 7: report PDFs and the public verification.
                "/api/v1/reports",
                "/api/v1/public/report-verifications",
                // MARA Stage 8: invoicing from reports.
                "/api/v1/report-invoicing",
                // MARA Stage 10: the add-on on the billing page, and phones' pushes (pulling stays open).
                "/api/v1/billing/addons/mara",
                "/api/v1/mobile/sync/push",
            ).flatMap { listOf(it, "$it/**") }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
internal class MaraFeatureConfiguration(
    private val guard: MaraFeatureGuard,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guard).addPathPatterns(MaraFeatureGuard.PATHS)
    }
}
