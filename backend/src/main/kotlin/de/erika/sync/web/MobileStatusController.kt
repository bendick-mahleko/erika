package de.erika.sync.web

import de.erika.contract.mobile.api.MobileApi
import de.erika.contract.mobile.model.AppUpdate
import de.erika.contract.mobile.model.MobileStatus
import de.erika.contract.mobile.model.ProtocolVersions
import de.erika.platform.runtime.RuntimeInstance
import de.erika.sync.MobileProperties
import de.erika.sync.SyncProtocol
import de.erika.sync.updateAdvice
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime

/** `GET /mobile/status` (api/mobile.yaml): answers without a token, before sign-in. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileStatusController(
    private val props: MobileProperties,
    private val clock: Clock = Clock.systemUTC(),
) : MobileApi {
    override fun getMobileStatus(xAppVersion: String?): ResponseEntity<MobileStatus> =
        ResponseEntity.ok(
            MobileStatus(
                protocol = ProtocolVersions(current = SyncProtocol.CURRENT, minimum = SyncProtocol.MINIMUM),
                minAppVersion = props.minAppVersion,
                recommendedAppVersion = props.recommendedAppVersion,
                appUpdate = AppUpdate.valueOf(updateAdvice(xAppVersion, props).name),
                serverTime = OffsetDateTime.now(clock),
            ),
        )
}
