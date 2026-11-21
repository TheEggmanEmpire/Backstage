package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.ui.Fixtures.snapshot
import org.junit.Assert.*
import org.junit.Test

class NotificationApprovalSecurityTest {
    @Test fun aCompleteQuestionAtTheNotificationLimitOffersApproval() {
        val question = "x".repeat(TaskNotificationModel.MAX_TEXT)
        val model = TaskNotificationModel.from(snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion(question, true)))
        assertEquals(question, model.text)
        assertTrue(TaskNotificationModel.Action.Approve in model.actions)
    }
    @Test fun aQuestionBeyondTheNotificationLimitRequiresInAppReview() {
        val question = "x".repeat(TaskNotificationModel.MAX_TEXT) + " delete all messages"
        val model = TaskNotificationModel.from(snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion(question, true)))
        assertFalse(TaskNotificationModel.Action.Approve in model.actions)
        assertFalse(TaskNotificationModel.Action.Reply in model.actions)
        assertTrue(TaskNotificationModel.Action.Open in model.actions)
        assertTrue(model.text.contains("Open Backstage to review"))
        assertTrue(model.text.length <= TaskNotificationModel.MAX_TEXT)
    }
}
