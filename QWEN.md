# NotifWebhook — Android-приложение для пересылки уведомлений через Webhook

## Обзор проекта

**NotifWebhook** — это Android-приложение (API 34+, Android 14+ only), которое перехватывает уведомления других приложений через `NotificationListenerService` и отправляет их на указанный webhook в формате JSON (HTTP POST).

### Основной сценарий

Пользователь устанавливает приложение, предоставляет доступ к уведомлениям, вводит URL webhook-получателя и включает пересылку. Все уведомления (или выбранных приложений) отправляются на указанный URL как POST-запросы с JSON-полезной нагрузкой.

## Технологии и зависимости

| Технология | Версия |
|---|---|
| Kotlin | JVM 17 |
| compileSdk / targetSdk / minSdk | 34 (Android 14) |
| Gradle / Android Gradle Plugin | build.gradle |
| Coroutines | 1.8.1 |
| Material Components | 1.12.0 |
| AndroidX Core-KTX | 1.13.1 |
| AppCompat | 1.7.0 |
| RecyclerView | 1.3.2 |

## Архитектура

```
NotificationListenerService   ← система биндит при включении NLS permission
        │  onNotificationPosted()
        │  buildPayload() → JSONObject
        │  sendToWebhook()  ← Coroutine IO dispatcher
        ▼
   Webhook HTTP POST

ForegroundKeepAliveService   ← удерживает процесс живым (START_STICKY)
        │  foregroundServiceType = specialUse (API 34 обязательно)
        │  startForeground(id, notification)
        ▼
   Постоянное уведомление в статус-баре

BootReceiver  ← BOOT_COMPLETED → ForegroundKeepAliveService.start()
AppPrefs      ← SharedPreferences singleton
MainActivity  ← UI: статус, URL, переключатели, список приложений
```

### Компоненты

| Файл | Описание |
|---|---|
| `MainActivity.kt` | Главный UI: статус слушателя, ввод webhook URL, переключатели, список установленных приложений с выбором |
| `NotificationListenerService.kt` | Ядро приложения — перехват уведомлений, дедупликация (окно 3 сек), формирование JSON, HTTP POST |
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
  "sub_text":         "",
  "category":         "msg",
  "priority":         0,
  "notification_id":  12345,
  "channel_id":       "messages",
  "timestamp_iso":    "2026-04-09T20:00:00.000Z",
  "timestamp_ms":     1744228800000
}
```

## Сборка и запуск

### Требования

- Android Studio (рекомендуется последняя стабильная версия)
- JDK 17
- Android SDK 34

### Сборка

```bash
# Из корня проекта (если есть обёртка gradlew)
./gradlew assembleDebug

# Или через Android Studio: Build → Make Project
```

### Установка на устройство

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Запуск

После установки:
1. Предоставить доступ к уведомлениям в системных настройках
2. Отключить оптимизацию батареи для приложения
3. Ввести webhook URL и нажать «Сохранить»
4. Нажать «Тест POST» для проверки соединения
5. Включить «Пересылать уведомления»
6. Выбрать приложения для отслеживания (пусто = все)

## Особенности Android 14+ (API 34)

| Проблема | Решение |
|---|---|
| `startService(NLS)` не работает | Только системный bind через permission |
| Сервис убивается OEM | `ForegroundKeepAliveService` с `START_STICKY` |
| `foregroundServiceType` обязателен | `specialUse` в манифесте |
| `POST_NOTIFICATIONS` permission | Запрашивается в `onResume` |
| Агрессивная батарея Xiaomi/Huawei | Кнопка исключения из оптимизации |
| Дубли уведомлений | Дедупликация: LinkedHashMap с окном 3 сек |

## Разрешения (Permissions)

- `INTERNET` — HTTP-запросы к webhook
- `ACCESS_NETWORK_STATE` — проверка сети
- `FOREGROUND_SERVICE` — foreground-сервис
- `FOREGROUND_SERVICE_SPECIAL_USE` — тип specialUse для API 34
- `POST_NOTIFICATIONS` — показ уведомлений (API 33+)
- `RECEIVE_BOOT_COMPLETED` — автозапуск
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — исключение из оптимизации батареи
- `BIND_NOTIFICATION_LISTENER_SERVICE` — для NLS (системное)

## Конвенции кода

- **Язык**: Kotlin, JVM target 17
- **Корутины**: `Dispatchers.IO` для сетевых операций, `Dispatchers.Main` для UI
- **SharedPreferences**: потокобезопасный singleton `AppPrefs`
- **Дедупликация**: `LinkedHashMap` с окном 3 секунды, максимум 50 записей
- **Сетевые запросы**: стандартная `HttpURLConnection`, синхронные вызовы в IO-диспетчере
- **UI**: Material Components, кастомный layout без Compose, `RecyclerView` для списка приложений
- **Логи**: тег `NLS_Webhook` для ListenerService, `KeepAliveService` для foreground-сервиса, `BootReceiver` для ресивера
