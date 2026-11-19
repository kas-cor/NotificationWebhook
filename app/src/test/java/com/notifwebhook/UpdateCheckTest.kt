package com.notifwebhook

import com.notifwebhook.ui.parseReleaseTagFromUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckTest {

    @Test
    fun `extracts tag from latest release redirect URL`() {
        assertEquals(
            "v1.6.0",
            parseReleaseTagFromUrl(
                "https://github.com/kas-cor/NotificationWebhook/releases/tag/v1.6.0"
            )
        )
    }

    @Test
    fun `extracts tag with query and fragment`() {
        assertEquals(
            "v1.6.0",
            parseReleaseTagFromUrl(
                "https://github.com/kas-cor/NotificationWebhook/releases/tag/v1.6.0?foo=bar#notes"
            )
        )
    }

    @Test
    fun `returns null for a page that is not a release tag`() {
        assertNull(
            parseReleaseTagFromUrl(
                "https://github.com/kas-cor/NotificationWebhook/releases"
            )
        )
    }
}
