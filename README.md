# NotifWebhook — Android 14+ (API 34)

Приложение перехватывает уведомления других приложений через `NotificationListenerService`
и отправляет их на указанный webhook как JSON (HTTP POST).

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

## JSON payload

```json
{
  "app_package":    "org.telegram.messenger",
  "app_name":       "Telegram",
  "title":          "Александр",
  "text":           "Привет!",
  "sub_text":       "",
  "category":       "msg",
  "priority":       0,
  "notification_id": 12345,
  "channel_id":     "messages",
  "timestamp_iso":  "2026-04-09T20:00:00.000Z",
  "timestamp_ms":   1744228800000
}
```

## Настройка (шаги для пользователя)

1. Установить APK
2. «Предоставить доступ к уведомлениям» → включить в системных настройках
3. «Отключить оптимизацию батареи» → разрешить
4. Ввести webhook URL, нажать «Сохранить»
5. Нажать «Тест POST» — убедиться в HTTP 200
6. Включить переключатель «Пересылать уведомления»
7. Выбрать нужные приложения (пусто = все)

## Android 14+ особенности

| Проблема | Решение |
|---|---|
| `startService(NLS)` не работает | Только системный bind через permission |
| Сервис убивается OEM | `ForegroundKeepAliveService` с `START_STICKY` |
| `foregroundServiceType` обязателен | `specialUse` в манифесте |
| `POST_NOTIFICATIONS` permission | Запрашивается в `onResume` |
| Агрессивная батарея Xiaomi/Huawei | Кнопка исключения из оптимизации |
| Дубли уведомлений | Дедупликация: LinkedHashMap с окном 3 сек |
