package de.erika.feedback.web

import de.erika.contract.api.FeedbackApi
import de.erika.contract.model.FeedbackRequest
import de.erika.feedback.FeedbackService
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class FeedbackController(
    private val feedback: FeedbackService,
) : FeedbackApi {
    /** Every role, tax advisors included: feedback changes no workspace data (ADR 0030). */
    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER', 'ADVISOR')")
    override fun sendFeedback(feedbackRequest: FeedbackRequest): ResponseEntity<Unit> {
        val principal = ErikaPrincipal.current()
        feedback.submit(
            principal.workspaceId,
            principal.userId,
            feedbackRequest.category.value,
            feedbackRequest.message,
            feedbackRequest.page,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }
}
