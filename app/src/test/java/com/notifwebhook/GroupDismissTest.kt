package com.notifwebhook

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class GroupDismissTest {

    @Test
    fun `cancels group summary when the last child is dismissed`() {
        val summary = notification("summary", "wildberries", groupSummary = true)
        val child = notification("child", "wildberries")

        assertEquals(
            "summary",
            NotificationListenerService.groupSummaryToCancel(
                arrayOf(summary, child), "child", "wildberries", targetIsGroupSummary = false
            )
        )
    }

    @Test
    fun `keeps summary while another child remains`() {
        val summary = notification("summary", "wildberries", groupSummary = true)
        val child = notification("child", "wildberries")
        val sibling = notification("sibling", "wildberries")

        assertNull(
            NotificationListenerService.groupSummaryToCancel(
                arrayOf(summary, child, sibling), "child", "wildberries", targetIsGroupSummary = false
            )
        )
    }

    @Test
    fun `does not cascade when the target is the summary itself`() {
        val summary = notification("summary", "wildberries", groupSummary = true)

        assertNull(
            NotificationListenerService.groupSummaryToCancel(
                arrayOf(summary), "summary", "wildberries", targetIsGroupSummary = true
            )
        )
    }

    @Test
    fun `returns null when group has no summary`() {
        val child = notification("child", "wildberries")

        assertNull(
            NotificationListenerService.groupSummaryToCancel(
                arrayOf(child), "child", "wildberries", targetIsGroupSummary = false
            )
        )
    }

    @Test
    fun `returns null when target is not grouped`() {
        val child = notification("child", null)

        assertNull(
            NotificationListenerService.groupSummaryToCancel(
                arrayOf(child), "child", null, targetIsGroupSummary = false
            )
        )
    }

    private fun notification(
        key: String,
        groupKey: String?,
        groupSummary: Boolean = false
    ): StatusBarNotification {
        val notification = mock(Notification::class.java).also {
            it.flags = if (groupSummary) Notification.FLAG_GROUP_SUMMARY else 0
        }
        return mock(StatusBarNotification::class.java).also {
            `when`(it.key).thenReturn(key)
            `when`(it.groupKey).thenReturn(groupKey)
            `when`(it.notification).thenReturn(notification)
        }
    }
}
