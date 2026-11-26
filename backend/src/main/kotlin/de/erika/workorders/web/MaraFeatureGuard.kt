package de.erika.workorders.web

import de.erika.platform.features.FeatureProperties
import de.erika.platform.runtime.RuntimeInstance
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
 * MARA's office endpoints answer 404 `FEATURE_DISABLED` unless `erika.features.mara` is on, so
 * the unfinished add-on stays invisible in production. The add-on entitlement (who has booked
 * it) follows in MARA Stage 10.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class MaraFeatureGuard(
    private val features: FeatureProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!features.mara) throw ErikaException("FEATURE_DISABLED", HttpStatus.NOT_FOUND, "Not available.")
        return true
    }

    companion object {
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
