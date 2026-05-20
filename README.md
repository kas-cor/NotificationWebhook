# NotifWebhook — Android 14+ (API 34)

[![GitHub](https://img.shields.io/badge/GitHub-kas--cor/NotificationWebhook-181717?logo=github)](https://github.com/kas-cor/NotificationWebhook)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/github/license/kas-cor/NotificationWebhook)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/kas-cor/NotificationWebhook?include_prereleases&logo=github)](https://github.com/kas-cor/NotificationWebhook/releases)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/kas-cor/NotificationWebhook/ci.yml?logo=github&label=CI)](https://github.com/kas-cor/NotificationWebhook/actions)
[![Coverage](https://img.shields.io/badge/coverage-63%25-A3D936?logo=codecov&logoColor=white&label=JaCoCo)](https://github.com/kas-cor/NotificationWebhook/actions?query=artifact%3Acoverage-report)

Приложение перехватывает уведомления других приложений через `NotificationListenerService`
и отправляет их на указанный webhook как JSON (HTTP POST).

## Возможности

- 📡 **Перехват уведомлений** из любых приложений (Telegram, WhatsApp, Gmail, банки и т.д.)
- 🌐 **Отправка на webhook** — HTTP POST с JSON-телом на любой URL
- 🎯 **Фильтр по приложениям** — выбрать конкретные приложения или все сразу
- 🚫 **Пропуск ongoing** — музыку, навигацию, системные уведомления можно исключить
- 🔁 **Автозапуск** — после перезагрузки устройства
- 🌗 **Material3 Design** — светлая и тёмная тема (автоматически, под систему)
- ✅ **Тестовая отправка** — встроенная кнопка для проверки webhook
- 🔐 **Bearer token** — опциональная авторизация через `Authorization: Bearer <токен>`

## JSON payload

```json
{
  "app_package":      "org.telegram.messenger",
  "app_name":         "Telegram",
  "title":            "Александр",
  "text":             "Привет!",
  "sub_text":         "3 новых сообщения",
  "category":         "msg",
  "priority":         0,
  "notification_id":  12345,
  "channel_id":       "messages",
  "timestamp_iso":    "2026-05-20T00:58:32.412Z",
  "timestamp_ms":     1779227912412
}
```

### Извлечение текста (fallback chain)

**Заголовок:** `EXTRA_TITLE_BIG` → `EXTRA_CONVERSATION_TITLE` → `EXTRA_TITLE` → `tickerText` → название приложения

**Текст:** `MessagingStyle.messages` (чаты) → `EXTRA_BIG_TEXT` → `EXTRA_TEXT_LINES` → `EXTRA_TEXT` → `EXTRA_INFO_TEXT` → `EXTRA_SUMMARY_TEXT` → `tickerText` → заголовок

## Архитектура

```
NotificationListenerService ← системный bind через BIND_NOTIFICATION_LISTENER_SERVICE
        │  onNotificationPosted()
        │  → дедупликация (окно 3 сек)
        │  → buildPayload() → JSONObject
        │  → sendToWebhook() ← Coroutine IO dispatcher
        ▼
   Webhook HTTP POST

ForegroundKeepAliveService  ← START_STICKY foreground-сервис
        │  удерживает процесс от убийства (особенно на Xiaomi/Huawei)
        │  foregroundServiceType = specialUse (API 34)
        │  requestRebind(NLS) при запуске
        ▼
   Постоянное уведомление в статус-баре
```

## Компоненты

| Файл | Назначение |
|---|---|
| `NotificationListenerService.kt` | Ядро: перехват, дедупликация, формирование JSON, HTTP POST |
| `MainActivity.kt` | UI: статус, webhook URL, переключатели, список приложений |
| `ForegroundKeepAliveService.kt` | Foreground-сервис для удержания процесса |
| `BootReceiver.kt` | Автозапуск после перезагрузки / обновления |
| `AppPrefs.kt` | SharedPreferences singleton (потокобезопасный) |

## Настройка (шаги для пользователя)

1. **Установить APK**
2. **Предоставить доступ к уведомлениям:**
   - Нажмите «Предоставить доступ» в приложении
   - В системных настройках найдите **NotifWebhook** → включите
3. **Отключить оптимизацию батареи:**
   - Нажмите кнопку в приложении → разрешите
4. **Ввести webhook URL** → **Сохранить**
5. *(опционально)* **Ввести Bearer token** в поле ниже URL → **Сохранить** (токен будет добавлен как `Authorization: Bearer ...` к каждому запросу)
6. **Нажать «Тест POST»** — убедиться, что HTTP 200
7. **Включить «Пересылать уведомления»**
8. *(опционально)* Выбрать конкретные приложения (пусто = все)

### Для Xiaomi / HyperOS / MIUI

Система безопасности Xiaomi агрессивно блокирует фоновые сервисы.
Дополнительно сделайте:

1. **Настройки → Приложения → Управление → NotifWebhook**
   - Включите **«Автозапуск»**
2. **Экономия энергии → NotifWebhook → «Без ограничений»**
3. **Закрепите NotifWebhook** в списке последних приложений

## Скриншоты дизайна

- **Material 3** — карточки с закруглениями, акцентный синий цвет
- **Тёмная тема** — автоматически под систему (DayNight)
- **Секции:** СТАТУС, WEBHOOK URL, НАСТРОЙКИ, ПРИЛОЖЕНИЯ
- **Bearer token** — поле ввода под webhook URL с переключателем видимости пароля

## Android 14+ особенности

| Проблема | Решение |
|---|---|
| `startService()` для NLS не работает | Только системный bind через `BIND_NOTIFICATION_LISTENER_SERVICE` |
| Сервис убивается OEM | `ForegroundKeepAliveService` c `START_STICKY` |
| `foregroundServiceType` обязателен | `specialUse` + декларация в манифесте |
| `POST_NOTIFICATIONS` permission | Запрашивается в `onResume` (API 33+) |
| Агрессивная батарея Xiaomi | Кнопка исключения из оптимизации + «Автозапуск» вручную |
| Дубли уведомлений | Дедупликация: `LinkedHashMap` с окном 3 сек, до 50 записей |
| NLS отключается после перезапуска | `requestRebind()` при старте сервиса + повтор через 5 сек |

## Технологии

| Технология | Версия |
|---|---|
| Kotlin / JVM | 17 |
| compileSdk / minSdk | 34 |
| Material Components | 1.12.0 |
| Coroutines | 1.8.1 |
| AndroidX Core-KTX | 1.13.1 |
| AppCompat | 1.7.0 |
| RecyclerView | 1.3.2 |

## Тестирование

Проект содержит **22 unit-теста** для `NotificationListenerService` — ядра приложения.

| Группа | Тестов | Что проверяют |
|---|---|---|
| `resolveTitle` | 8 | Приоритет заголовков: BigTitle → Title → tickerText → "" |
| `resolveText` | 11 | Приоритет текста: BigText → TextLines → Text → SummaryText → tickerText → "" |
| `isOngoing` | 3 | Определение `FLAG_ONGOING_EVENT` |

**Стек:** JUnit 4.13.2 + Mockito 5.11.0 (inline mock maker для `Bundle`).

### Запуск тестов

```bash
# Unit-тесты
./gradlew test

# Покрытие кода (JaCoCo)
./gradlew jacocoTestReport
```

После `jacocoTestReport` откройте в браузере:
```
app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### CI

При каждом пуше в `main` тесты запускаются автоматически (шаг `Run unit tests`). Отчёт JaCoCo (HTML + XML) загружается как артефакт `coverage-report` и хранится 14 дней.

### Добавление тестов

Тесты находятся в `app/src/test/java/com/notifwebhook/`. Файл конфигурации inline mock maker:
```
app/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker
```

---

## Сборка

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

<p align="center">
  <a href="https://github.com/kas-cor/NotificationWebhook">📦 GitHub</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/kas-cor/NotificationWebhook/issues">🐛 Сообщить об ошибке</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/kas-cor/NotificationWebhook/discussions">💬 Обсуждения</a>
</p>
