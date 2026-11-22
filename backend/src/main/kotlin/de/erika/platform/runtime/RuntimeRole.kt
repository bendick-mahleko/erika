package de.erika.platform.runtime

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.UUID

/** The role this process plays. Exactly one of the profiles `api` or `worker` must be active. */
enum class RuntimeRole { API, WORKER }

@Component
class RuntimeInstance(
    environment: Environment,
) {
    val role: RuntimeRole =
        run {
            val api = environment.acceptsProfiles(Profiles.of(PROFILE_API))
            val worker = environment.acceptsProfiles(Profiles.of(PROFILE_WORKER))
            check(api xor worker) {
                "Activate exactly one of the Spring profiles '$PROFILE_API' or '$PROFILE_WORKER' " +
                    "(active: ${environment.activeProfiles.joinToString()})"
            }
            if (api) RuntimeRole.API else RuntimeRole.WORKER
        }

    /** Unique per process; used for job leases and heartbeats. */
    val id: String = "${role.name.lowercase()}-${hostName()}-${UUID.randomUUID().toString().take(8)}"

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown")

    companion object {
        const val PROFILE_API = "api"
        const val PROFILE_WORKER = "worker"
    }
}
