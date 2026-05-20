# NotifWebhook — Android-приложение для пересылки уведомлений через Webhook

## Обзор проекта

**NotifWebhook** — это Android-приложение (API 34+, Android 14+ only), которое перехватывает уведомления других приложений через `NotificationListenerService` и отправляет их на указанный webhook в формате JSON (HTTP POST).

### Основной сценарий

Пользователь устанавливает приложение, предоставляет доступ к уведомлениям, вводит URL webhook-получателя и (опционально) Bearer token для авторизации. Все уведомления (или выбранных приложений) отправляются на указанный URL как POST-запросы с JSON-полезной нагрузкой и заголовком `Authorization: Bearer <token>` (если токен задан).

---

## Технологии и зависимости

| Технология | Версия |
|---|---|
| Kotlin / JVM | 17 |
| compileSdk / targetSdk / minSdk | 34 (Android 14) |
| Gradle / Android Gradle Plugin | 8.5 / 8.2.2 |
| Coroutines | 1.8.1 |
| Material Components | 1.12.0 |
| AndroidX Core-KTX | 1.13.1 |
| AppCompat | 1.7.0 |
| RecyclerView | 1.3.2 |
| JaCoCo | 0.8.11 |
| JUnit | 4.13.2 |
| Mockito | 5.11.0 (inline mock maker) |

---

## Архитектура

```
NotificationListenerService   ← системный bind через BIND_NOTIFICATION_LISTENER_SERVICE
        │  onNotificationPosted()
        │  → дедупликация (LinkedHashMap, окно 3 сек, до 50 записей)
        │  → resolveTitle() / resolveText() — fallback chain
        │  → buildPayload() → JSONObject
        │  → sendToWebhook() ← Coroutine IO dispatcher
        │    + Authorization: Bearer <token> (если задан)
        ▼
   Webhook HTTP POST

ForegroundKeepAliveService   ← START_STICKY foreground-сервис (specialUse, API 34)
        │  удерживает процесс от убийства (особенно на Xiaomi/Huawei)
        │  requestRebind(NLS) при запуске
        ▼
   Постоянное уведомление в статус-баре

BootReceiver  ← BOOT_COMPLETED / MY_PACKAGE_REPLACED → ForegroundKeepAliveService.start()
AppPrefs      ← SharedPreferences singleton (потокобезопасный)
MainActivity  ← UI: статус слушателя, webhook URL, переключатели, список приложений
```

### Компоненты

| Файл | Описание |
|---|---|
| `MainActivity.kt` | Главный UI: статус, ввод webhook URL, Bearer token, переключатели, список установленных приложений |
| `NotificationListenerService.kt` | Ядро: перехват, дедупликация, resolveTitle/resolveText, JSON, HTTP POST |
| `ForegroundKeepAliveService.kt` | Foreground-сервис для удержания процесса (specialUse, API 34) |
| `BootReceiver.kt` | Автозапуск после перезагрузки / обновления пакета |
| `AppPrefs.kt` | Singleton-обёртка над SharedPreferences |

### JSON payload

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

---

## Unit-тесты

**22 теста** для `NotificationListenerServiceTest`:

| Группа | Тестов | Что проверяют |
|---|---|---|
| `resolveTitle` | 8 | Приоритет заголовков: BigTitle → Title → tickerText → "", пустые/пробельные значения |
| `resolveText` | 11 | Приоритет текста: BigText → TextLines → Text → SummaryText → tickerText → "" |
| `isOngoing` | 3 | `FLAG_ONGOING_EVENT`, без флага, комбинация флагов |

**Запуск:** `./gradlew test`
**Покрытие:** `./gradlew jacocoTestReport` → отчёт в `app/build/reports/jacoco/jacocoTestReport/html/index.html`

**Моки:** `Bundle` (final class) — мокается через inline mock maker (`mockito-extensions/org.mockito.plugins.MockMaker`). `Notification` — поля `tickerText`, `flags` устанавливаются напрямую на mock-объекте.

---

## CI/CD (GitHub Actions)

Workflow: `.github/workflows/ci.yml`

### Триггеры

- **Push** в `main`
- **Pull Request** в `main`
- **Push тега** `v*` (например, `v1.1`)
- **Ручной запуск** через `workflow_dispatch`

### Шаги сборки

1. Checkout + JDK 17 + Android SDK licenses
2. Кеширование Gradle
3. **Lint** (`lintRelease`)
4. **Unit-тесты** (`test`)  
5. **JaCoCo coverage** (`jacocoTestReport`) → HTML и XML артефакты
6. **Debug APK** (`assembleDebug`)
7. **Декодирование keystore** из секрета `KEYSTORE_BASE64`
8. **Signed release APK** (`assembleRelease`) с `KEYSTORE_PASSWORD`
9. **Загрузка артефактов:** debug APK, signed release APK (30 дней), unsigned (fallback)

### Релизы

При пуше тега `v*` дополнительно запускается job `release`:
- Скачивает signed APK
- Создаёт GitHub Release с телом и APK

### Секреты GitHub

| Secret | Описание |
|---|---|
| `KEYSTORE_BASE64` | `notwebhook-release.jks` в base64 (`base64 -w0 notwebhook-release.jks`) |
| `KEYSTORE_PASSWORD` | Пароль от keystore |

---

## Настройка (шаги для пользователя)

1. **Установить APK** (скачать из [Releases](https://github.com/kas-cor/NotificationWebhook/releases))
2. **Предоставить доступ к уведомлениям:**
   - Нажмите «Предоставить доступ» в приложении
   - В системных настройках найдите NotifWebhook → включите
3. **Отключить оптимизацию батареи:**
   - Нажмите кнопку в приложении → разрешите
4. **Ввести webhook URL** → **Сохранить**
5. *(опционально)* **Ввести Bearer token** в поле ниже URL → **Сохранить** (добавляется как `Authorization: Bearer <token>` к каждому запросу)
6. **Нажать «Тест POST»** — убедиться, что HTTP 200
7. **Включить «Пересылать уведомления»**
8. *(опционально)* Выбрать конкретные приложения (пусто = все)

### Для Xiaomi / HyperOS / MIUI

Система безопасности Xiaomi агрессивно блокирует фоновые сервисы:

1. **Настройки → Приложения → Управление → NotifWebhook**
   - Включите **«Автозапуск»**
2. **Экономия энергии → NotifWebhook → «Без ограничений»**
3. **Закрепите NotifWebhook** в списке последних приложений

---

## Особенности Android 14+ (API 34)

| Проблема | Решение |
|---|---|
| `startService()` для NLS не работает | Только системный bind через `BIND_NOTIFICATION_LISTENER_SERVICE` |
| Сервис убивается OEM | `ForegroundKeepAliveService` с `START_STICKY` |
| `foregroundServiceType` обязателен | `specialUse` в манифесте |
| `POST_NOTIFICATIONS` permission | Запрашивается в `onResume` (API 33+) |
| Агрессивная батарея Xiaomi/Huawei | Кнопка исключения из оптимизации + «Автозапуск» вручную |
| Дубли уведомлений | Дедупликация: LinkedHashMap с окном 3 сек, макс. 50 записей |
| NLS отключается после перезапуска | `requestRebind()` при старте + повтор через 5 сек |

---

## Разрешения (Permissions)

- `INTERNET` — HTTP-запросы к webhook
- `ACCESS_NETWORK_STATE` — проверка сети
- `FOREGROUND_SERVICE` — foreground-сервис
- `FOREGROUND_SERVICE_SPECIAL_USE` — тип specialUse для API 34
- `POST_NOTIFICATIONS` — показ уведомлений (API 33+)
- `RECEIVE_BOOT_COMPLETED` — автозапуск
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — исключение из оптимизации батареи
- `BIND_NOTIFICATION_LISTENER_SERVICE` — для NLS (системное)

---

## Конвенции кода

- **Язык**: Kotlin, JVM target 17
- **Корутины**: `Dispatchers.IO` для сетевых операций, `Dispatchers.Main` для UI
- **SharedPreferences**: потокобезопасный singleton `AppPrefs`
- **Дедупликация**: `LinkedHashMap` с окном 3 секунды, максимум 50 записей
- **Сетевые запросы**: стандартная `HttpURLConnection`, синхронные вызовы в IO-диспетчере. Если в настройках задан Bearer token, добавляется заголовок `Authorization: Bearer <token>`
- **UI**: Material Components, кастомный layout без Compose, `RecyclerView` для списка приложений
- **Логи**: тег `NLS_Webhook` для ListenerService, `KeepAliveService` для foreground-сервиса, `BootReceiver` для ресивера
- **Тестирование**: JUnit + Mockito (inline mock maker для final-классов), 22 unit-теста
- **CI**: lint → test → JaCoCo → assembleDebug → assembleRelease → артефакты
- **Подпись APK**: через секреты `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD`

---

## Сборка

```bash
# Debug
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release (подпись — если настроены секреты локально)
export KEYSTORE_PASSWORD='ваш_пароль'
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# Тесты + покрытие
./gradlew test
./gradlew jacocoTestReport
# Открыть: app/build/reports/jacoco/jacocoTestReport/html/index.html
```
