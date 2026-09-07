package com.notifwebhook

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notifwebhook.ui.MainScreen
import com.notifwebhook.ui.NotifWebhookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MainScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var prefs: AppPrefs

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Чтобы HomeTab не запускал системный диалог запроса POST_NOTIFICATIONS
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs = AppPrefs(app.getSharedPreferences("ui_test_prefs", Context.MODE_PRIVATE))
        var theme = "system"
        var lang = ""
        composeRule.setContent {
            NotifWebhookTheme {
                MainScreen(
                    prefs = prefs,
                    themeMode = theme,
                    locale = lang,
                    onThemeChange = { theme = it },
                    onLocaleChange = { lang = it }
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Переключение вкладок (дефолтная локаль в Robolectric = en)
    // ---------------------------------------------------------------------

    @Test
    fun bottomNavigation_switchesBetweenAllTabs() {
        // Стартовая вкладка — Home
        composeRule.onNodeWithText("STATUS").assertIsDisplayed()

        composeRule.onNodeWithText("Exclusions").performClick()
        composeRule.onNodeWithText("APPS").assertIsDisplayed()

        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("SEND HISTORY").assertIsDisplayed()

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("SETTINGS").assertIsDisplayed()

        // Возврат на Home
        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithText("STATUS").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------
    // Диалог добавления правила: валидация
    // ---------------------------------------------------------------------

    @Test
    fun addRuleDialog_showsValidationErrorOnEmptyPattern() {
        openAddRuleDialog()

        // Пустой шаблон — диалог не закрывается, показывается ошибка
        composeRule.onNodeWithText("Add").performClick()
        composeRule.onNodeWithText("Enter text to search").assertIsDisplayed()

        // Отмена не добавляет правило
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, prefs.getExclusionRules().size)
        composeRule.onNodeWithText("No rules yet — all notifications are forwarded")
            .assertIsDisplayed()
    }

    @Test
    fun addRuleDialog_addsRuleAndPersists() {
        openAddRuleDialog()

        // Пустой шаблон не проходит — правило не создаётся
        composeRule.onNodeWithText("Add").performClick()
        assertEquals(0, prefs.getExclusionRules().size)

        // Вводим шаблон (поле по умолчанию — «Title (title)») и добавляем
        composeRule
            .onNode(hasSetTextAction() and hasText("Text to search"))
            .performTextInput("Bank")
        composeRule.onNodeWithText("Add").performClick()

        // Диалог закрыт, правило сохранено в prefs и показано в списке
        val rules = prefs.getExclusionRules()
        assertEquals(1, rules.size)
        assertEquals("title", rules[0].field)
        assertEquals("Bank", rules[0].pattern)

        composeRule.onNodeWithText("Bank").assertIsDisplayed()
        composeRule.onNodeWithText("Title").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------
    // История отправки
    // ---------------------------------------------------------------------

    @Test
    fun historyTab_showsEntriesAndClears() {
        prefs.addHistoryEntry(
            WebhookEntry(
                timestamp = System.currentTimeMillis() - 1000,
                appPackage = "com.example.app",
                appName = "FailApp",
                title = "Crash",
                text = "Connection error",
                success = false,
                httpCode = 0
            )
        )
        prefs.addHistoryEntry(
            WebhookEntry(
                timestamp = System.currentTimeMillis(),
                appPackage = "org.telegram.messenger",
                appName = "Telegram",
                title = "Message",
                text = "Hello!",
                success = true,
                httpCode = 200,
                classifyStatus = "dismiss"
            )
        )

        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("HTTP 200").assertIsDisplayed()
        // Статус классификации показывается, когда он есть
        composeRule.onNodeWithText("Promo: swiped").assertIsDisplayed()
        composeRule.onNodeWithText("FailApp").assertIsDisplayed()

        composeRule.onNodeWithText("Clear history").performClick()
        composeRule.onNodeWithText("No records").assertIsDisplayed()
        assertTrue(prefs.getHistory().isEmpty())
    }

    // ---------------------------------------------------------------------
    // Классификация промо (переключатель на вкладке Settings)
    // ---------------------------------------------------------------------

    @Test
    fun settings_classificationTogglePersists() {
        // По умолчанию включено
        assertTrue(prefs.classificationEnabled)
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Auto-swipe promos")
            .performScrollTo()
            .assertIsDisplayed()

        // На вкладке Settings 3 переключателя: forward, ongoing, auto-swipe
        composeRule.onAllNodes(isToggleable())[2]
            .performScrollTo()
            .performClick()
        assertTrue(!prefs.classificationEnabled)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun openAddRuleDialog() {
        composeRule.onNodeWithText("Exclusions").performClick()
        composeRule.onNodeWithText("Add rule").performClick()
        composeRule.onNodeWithText("Add exclusion rule").assertIsDisplayed()
    }
}
