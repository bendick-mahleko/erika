package de.erika.identity.web

import de.erika.contract.api.NotificationsApi
import de.erika.contract.model.NotificationSettings
import de.erika.identity.IdentityErrors
import de.erika.identity.MembershipRepository
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController

/** The signed-in user's notification preferences in the session's workspace. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class NotificationSettingsController(
    private val memberships: MembershipRepository,
) : NotificationsApi {
    override fun getNotificationSettings(): ResponseEntity<NotificationSettings> {
        val principal = ErikaPrincipal.current()
        val notify = memberships.notifyNewDocuments(principal.userId) ?: throw IdentityErrors.notFound("MEMBER")
        return ResponseEntity.ok(NotificationSettings(emailOnNewDocuments = notify))
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateNotificationSettings(notificationSettings: NotificationSettings): ResponseEntity<NotificationSettings> {
        val principal = ErikaPrincipal.current()
        if (!memberships.setNotifyNewDocuments(principal.userId, notificationSettings.emailOnNewDocuments)) {
            throw IdentityErrors.notFound("MEMBER")
        }
        return ResponseEntity.ok(notificationSettings)
    }
}
