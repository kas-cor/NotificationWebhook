package com.notifwebhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppPrefsTest {

    private lateinit var prefs: AppPrefs
    private lateinit var inMemorySp: InMemorySharedPreferences

    @Before
    fun setUpPrefs() {
        inMemorySp = InMemorySharedPreferences()
        prefs = AppPrefs::class.java.getDeclaredConstructor(
            android.content.SharedPreferences::class.java
        ).apply { isAccessible = true }
            .newInstance(inMemorySp)
    }

    // -------------------------------------------------------------------------
    // WebhookEntry data class tests
    // -------------------------------------------------------------------------

    @Test
    fun `WebhookEntry stores all fields correctly`() {
        val entry = WebhookEntry(
            timestamp = 1000L,
            appPackage = "com.example.app",
            appName = "Example App",
            title = "Test Title",
            text = "Test text content",
            success = true,
            httpCode = 200
        )

        assertEquals(1000L, entry.timestamp)
        assertEquals("com.example.app", entry.appPackage)
        assertEquals("Example App", entry.appName)
        assertEquals("Test Title", entry.title)
        assertEquals("Test text content", entry.text)
        assertTrue(entry.success)
        assertEquals(200, entry.httpCode)
    }

    @Test
    fun `WebhookEntry default success is false`() {
        val entry = WebhookEntry(
            timestamp = 0L,
            appPackage = "",
            appName = "",
            title = "",
            text = "",
            success = false,
            httpCode = 0
        )
        assertFalse(entry.success)
        assertEquals(0, entry.httpCode)
    }

    // -------------------------------------------------------------------------
    // WebhookEntry JSON roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun `WebhookEntry toJson and fromJson roundtrip`() {
        val original = WebhookEntry(
            timestamp = 1000L,
            appPackage = "com.example.app",
            appName = "Example App",
            title = "Test Title",
            text = "Test text content",
            success = true,
            httpCode = 200
        )

        val json = original.toJson()
        val restored = WebhookEntry.fromJson(json)

        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.appPackage, restored.appPackage)
        assertEquals(original.appName, restored.appName)
        assertEquals(original.title, restored.title)
        assertEquals(original.text, restored.text)
        assertEquals(original.success, restored.success)
        assertEquals(original.httpCode, restored.httpCode)
    }

    // -------------------------------------------------------------------------
    // ExclusionRule data class tests
    // -------------------------------------------------------------------------

    @Test
    fun `ExclusionRule stores all fields correctly`() {
        val rule = ExclusionRule(
            id = "test-id-123",
            field = "title",
            pattern = "Claw"
        )

        assertEquals("test-id-123", rule.id)
        assertEquals("title", rule.field)
        assertEquals("Claw", rule.pattern)
    }

    @Test
    fun `ExclusionRule generates unique id by default`() {
        val rule1 = ExclusionRule(field = "title", pattern = "a")
        val rule2 = ExclusionRule(field = "title", pattern = "b")

        assertTrue(rule1.id.isNotBlank())
        assertTrue(rule2.id.isNotBlank())
        assertTrue(rule1.id != rule2.id)
    }

    // -------------------------------------------------------------------------
    // ExclusionRule JSON roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun `ExclusionRule toJson and fromJson roundtrip`() {
        val original = ExclusionRule(id = "test-id", field = "title", pattern = "Claw")

        val json = original.toJson()
        val restored = ExclusionRule.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.field, restored.field)
        assertEquals(original.pattern, restored.pattern)
    }

    // -------------------------------------------------------------------------
    // AppPrefs — History (InMemorySharedPreferences)
    // -------------------------------------------------------------------------

    @Test
    fun `getHistory returns empty list initially`() {
        val history = prefs.getHistory()
        assertTrue(history.isEmpty())
    }

    @Test
    fun `addHistoryEntry stores a new entry`() {
        val entry = WebhookEntry(
            timestamp = 100L,
            appPackage = "com.test",
            appName = "Test",
            title = "Title",
            text = "Text",
            success = true,
            httpCode = 200
        )

        prefs.addHistoryEntry(entry)

        val history = prefs.getHistory()
        assertEquals(1, history.size)

        val stored = history[0]
        assertEquals(100L, stored.timestamp)
        assertEquals("com.test", stored.appPackage)
        assertEquals("Test", stored.appName)
        assertEquals("Title", stored.title)
        assertEquals("Text", stored.text)
        assertTrue(stored.success)
        assertEquals(200, stored.httpCode)
    }

    @Test
    fun `addHistoryEntry enforces max 50 entries`() {
        // Pre-fill with 50 entries via SharedPreferences directly
        val json = buildString {
            append("[")
            for (i in 1..50) {
                if (i > 1) append(",")
                append("""{"timestamp":$i,"app_package":"com.$i","app_name":"App $i","title":"t","text":"x","success":true,"http_code":200}""")
            }
            append("]")
        }
        inMemorySp.edit().putString("webhook_history", json).apply()

        // Add one more entry
        prefs.addHistoryEntry(
            WebhookEntry(
                timestamp = 999L,
                appPackage = "com.new",
                appName = "New",
                title = "New",
                text = "New",
                success = true,
                httpCode = 200
            )
        )

        val history = prefs.getHistory()
        assertEquals(50, history.size)

        // First entry should be com.2 (com.1 was evicted)
        assertEquals("com.2", history.first().appPackage)
        // Last entry should be com.new
        assertEquals("com.new", history.last().appPackage)
        assertEquals(999L, history.last().timestamp)
    }

    @Test
    fun `clearHistory removes the history key`() {
        // Store something first
        prefs.addHistoryEntry(
            WebhookEntry(0L, "com.test", "", "", "", true, 200)
        )
        assertTrue(prefs.getHistory().isNotEmpty())

        prefs.clearHistory()

        assertTrue(prefs.getHistory().isEmpty())
    }

    @Test
    fun `getHistory handles malformed JSON gracefully`() {
        inMemorySp.edit().putString("webhook_history", "not valid json{{{").apply()

        val history = prefs.getHistory()
        assertTrue(history.isEmpty())
    }

    // -------------------------------------------------------------------------
    // AppPrefs — Exclusion Rules (InMemorySharedPreferences)
    // -------------------------------------------------------------------------

    @Test
    fun `getExclusionRules returns empty list initially`() {
        val rules = prefs.getExclusionRules()
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `addExclusionRule stores a new rule`() {
        prefs.addExclusionRule(ExclusionRule(id = "r1", field = "title", pattern = "Claw"))

        val rules = prefs.getExclusionRules()
        assertEquals(1, rules.size)
        assertEquals("r1", rules[0].id)
        assertEquals("title", rules[0].field)
        assertEquals("Claw", rules[0].pattern)
    }

    @Test
    fun `removeExclusionRule removes the correct rule by id`() {
        // Setup: two rules
        inMemorySp.edit().putString(
            "exclusion_rules",
            """[{"id":"r1","field":"title","pattern":"Claw"},{"id":"r2","field":"text","pattern":"spam"}]"""
        ).apply()

        prefs.removeExclusionRule("r1")

        val rules = prefs.getExclusionRules()
        assertEquals(1, rules.size)
        assertEquals("r2", rules[0].id)

        // Verify handling non-existent rule doesn't crash
        prefs.removeExclusionRule("non_existent")
        assertEquals(1, prefs.getExclusionRules().size)
    }

    @Test
    fun `getExclusionRules handles malformed JSON gracefully`() {
        inMemorySp.edit().putString("exclusion_rules", "{{{broken").apply()

        val rules = prefs.getExclusionRules()
        assertTrue(rules.isEmpty())
    }
}
