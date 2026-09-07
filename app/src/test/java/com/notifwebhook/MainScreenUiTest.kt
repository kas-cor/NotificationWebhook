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

/**
 * Compose UI-тесты главного экрана (запускаются на JVM через Robolectric,
 * командой ./gradlew test — эмулятор не нужен).
 */
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
        composeRule.setContent {
            NotifWebhookTheme {
                MainScreen(prefs)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Переключение вкладок
    // ---------------------------------------------------------------------

    @Test
    fun bottomNavigation_switchesBetweenAllTabs() {
        // Стартовая вкладка — Главная
        composeRule.onNodeWithText("СТАТУС").assertIsDisplayed()

        composeRule.onNodeWithText("Приложения").performClick()
        composeRule.onNodeWithText("ПРИЛОЖЕНИЯ").assertIsDisplayed()

        composeRule.onNodeWithText("Правила").performClick()
        composeRule.onNodeWithText("ПРАВИЛА ИСКЛЮЧЕНИЙ").assertIsDisplayed()

        composeRule.onNodeWithText("История").performClick()
        composeRule.onNodeWithText("ИСТОРИЯ ОТПРАВКИ").assertIsDisplayed()

        // Возврат на Главную
        composeRule.onNodeWithText("Главная").performClick()
        composeRule.onNodeWithText("СТАТУС").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------
    // Диалог добавления правила: валидация
    // ---------------------------------------------------------------------

    @Test
    fun addRuleDialog_showsValidationErrorOnEmptyPattern() {
        openAddRuleDialog()

        // Пустой шаблон — диалог не закрывается, показывается ошибка
        composeRule.onNodeWithText("Добавить").performClick()
        composeRule.onNodeWithText("Введите текст для поиска").assertIsDisplayed()

        // Отмена не добавляет правило
        composeRule.onNodeWithText("Отмена").performClick()
        assertEquals(0, prefs.getExclusionRules().size)
        composeRule.onNodeWithText("Правил пока нет — пересылаются все уведомления")
            .assertIsDisplayed()
    }

    @Test
    fun addRuleDialog_addsRuleAndPersists() {
        openAddRuleDialog()

        // Пустой шаблон не проходит — правило не создаётся
        composeRule.onNodeWithText("Добавить").performClick()
        assertEquals(0, prefs.getExclusionRules().size)

        // Вводим шаблон (поле по умолчанию — «Заголовок (title)») и добавляем
        composeRule
            .onNode(hasSetTextAction() and hasText("Текст для поиска"))
            .performTextInput("Банк")
        composeRule.onNodeWithText("Добавить").performClick()

        // Диалог закрыт, правило сохранено в prefs и показано в списке
        val rules = prefs.getExclusionRules()
        assertEquals(1, rules.size)
        assertEquals("title", rules[0].field)
        assertEquals("Банк", rules[0].pattern)

        composeRule.onNodeWithText("Банк").assertIsDisplayed()
        composeRule.onNodeWithText("Заголовок").assertIsDisplayed()
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
                title = "Сбой",
                text = "Ошибка соединения",
                success = false,
                httpCode = 0
            )
        )
        prefs.addHistoryEntry(
            WebhookEntry(
                timestamp = System.currentTimeMillis(),
                appPackage = "org.telegram.messenger",
                appName = "Telegram",
                title = "Сообщение",
                text = "Привет!",
                success = true,
                httpCode = 200,
                classifyStatus = "dismiss"
            )
        )

        composeRule.onNodeWithText("История").performClick()
        // В UI записи показываются от новых к старым: сверху Telegram (HTTP 200)
        composeRule.onNodeWithText("Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("HTTP 200").assertIsDisplayed()
        // Статус классификации показывается, когда он есть
        composeRule.onNodeWithText("Промо: смахнуто").assertIsDisplayed()
        composeRule.onNodeWithText("FailApp").assertIsDisplayed()

        composeRule.onNodeWithText("Очистить историю").performClick()
        composeRule.onNodeWithText("Нет записей").assertIsDisplayed()
        assertTrue(prefs.getHistory().isEmpty())
    }

    // ---------------------------------------------------------------------
    // Классификация промо
    // ---------------------------------------------------------------------

    @Test
    fun homeSettings_classificationTogglePersists() {
        // По умолчанию включено
        assertTrue(prefs.classificationEnabled)
        composeRule.onNodeWithText("Автосмахивание промо")
            .performScrollTo()
            .assertIsDisplayed()

        // На Главной 3 переключателя: пересылка, ongoing, автосмахивание — берём третий
        composeRule.onAllNodes(isToggleable())[2]
            .performScrollTo()
            .performClick()
        assertTrue(!prefs.classificationEnabled)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun openAddRuleDialog() {
        composeRule.onNodeWithText("Правила").performClick()
        composeRule.onNodeWithText("Добавить правило").performClick()
        composeRule.onNodeWithText("Добавить правило исключения").assertIsDisplayed()
    }
}
