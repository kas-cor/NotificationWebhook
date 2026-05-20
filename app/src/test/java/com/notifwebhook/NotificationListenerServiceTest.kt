package com.notifwebhook

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NotificationListenerServiceTest {

    private lateinit var service: NotificationListenerService
    private lateinit var mockNotification: Notification
    private lateinit var extras: Bundle

    @Before
    fun setUp() {
        service = NotificationListenerService()
        mockNotification = mock(Notification::class.java)
        extras = mock(Bundle::class.java)
    }

    // -------------------------------------------------------------------------
    // resolveTitle
    // -------------------------------------------------------------------------

    @Test
    fun `resolveTitle returns EXTRA_TITLE_BIG when present`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)).thenReturn("Big Title")
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Small Title")

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Big Title", result)
    }

    @Test
    fun `resolveTitle returns EXTRA_TITLE when no big title`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Normal Title")

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Normal Title", result)
    }

    @Test
    fun `resolveTitle returns tickerText when all extras are null`() {
        mockNotification.tickerText = "Ticker Text"

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Ticker Text", result)
    }

    @Test
    fun `resolveTitle returns empty string when nothing is available`() {
        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("", result)
    }

    @Test
    fun `resolveTitle prefers big title over normal title`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)).thenReturn("Big Title")
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Small")

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Big Title", result)
    }

    @Test
    fun `resolveTitle prefers normal title over ticker`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Normal Title")
        mockNotification.tickerText = "Ticker"

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Normal Title", result)
    }

    @Test
    fun `resolveTitle skips empty big title`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)).thenReturn("")
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Fallback Title")

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Fallback Title", result)
    }

    @Test
    fun `resolveTitle skips blank big title`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)).thenReturn("   ")
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Actual Title")

        val result = service.resolveTitle(mockNotification, extras)
        assertEquals("Actual Title", result)
    }

    // -------------------------------------------------------------------------
    // resolveText
    // -------------------------------------------------------------------------

    @Test
    fun `resolveText returns empty string when nothing available`() {
        val result = service.resolveText(mockNotification, extras)
        assertEquals("", result)
    }

    @Test
    fun `resolveText returns tickerText as last resort`() {
        mockNotification.tickerText = "Ticker fallback"

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Ticker fallback", result)
    }

    @Test
    fun `resolveText returns EXTRA_TEXT when present`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Main text content")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Main text content", result)
    }

    @Test
    fun `resolveText returns EXTRA_BIG_TEXT over EXTRA_TEXT`() {
        `when`(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).thenReturn("Big expanded text")
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Normal text")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Big expanded text", result)
    }

    @Test
    fun `resolveText returns EXTRA_SUMMARY_TEXT when available`() {
        `when`(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)).thenReturn("Summary text")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Summary text", result)
    }

    @Test
    fun `resolveText joins EXTRA_TEXT_LINES when present`() {
        `when`(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
            .thenReturn(arrayOf("Line 1", "Line 2", "Line 3"))

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Line 1\nLine 2\nLine 3", result)
    }

    @Test
    fun `resolveText prefers EXTRA_BIG_TEXT over TEXT_LINES`() {
        `when`(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).thenReturn("Big text")
        `when`(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)).thenReturn(arrayOf("Line 1"))

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Big text", result)
    }

    @Test
    fun `resolveText returns empty for blank big text and falls through`() {
        `when`(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).thenReturn("   ")
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Main text")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Main text", result)
    }

    @Test
    fun `resolveText prefers EXTRA_TEXT over EXTRA_SUMMARY_TEXT`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Main text")
        `when`(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)).thenReturn("Summary")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Main text", result)
    }

    @Test
    fun `resolveText prefers TEXT_LINES over EXTRA_TEXT`() {
        `when`(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)).thenReturn(arrayOf("Line 1"))
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Main text")

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Line 1", result)
    }

    @Test
    fun `resolveText prefers EXTRA_TEXT over ticker`() {
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn("Main text")
        mockNotification.tickerText = "Ticker"

        val result = service.resolveText(mockNotification, extras)
        assertEquals("Main text", result)
    }

    // -------------------------------------------------------------------------
    // isOngoing
    // -------------------------------------------------------------------------

    @Test
    fun `notification with FLAG_ONGOING_EVENT is ongoing`() {
        mockNotification.flags = Notification.FLAG_ONGOING_EVENT
        val result = with(service) { mockNotification.isOngoing }
        assertTrue(result)
    }

    @Test
    fun `notification without FLAG_ONGOING_EVENT is not ongoing`() {
        mockNotification.flags = 0
        val result = with(service) { mockNotification.isOngoing }
        assertFalse(result)
    }

    @Test
    fun `notification with FLAG_ONGOING_EVENT and other flags is ongoing`() {
        mockNotification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_AUTO_CANCEL
        val result = with(service) { mockNotification.isOngoing }
        assertTrue(result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createMockSbn(packageName: String, postTime: Long): StatusBarNotification {
        return mock(StatusBarNotification::class.java).also { sbn ->
            `when`(sbn.packageName).thenReturn(packageName)
            `when`(sbn.postTime).thenReturn(postTime)
            `when`(sbn.id).thenReturn(42)
            `when`(sbn.notification).thenReturn(mockNotification)
        }
    }
}
