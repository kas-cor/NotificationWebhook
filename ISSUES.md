# Code Review: NotificationWebhook

> **Источник:** автоматический code review через kanban (воркер `deep-reasoner`, задача `t_904c0bf8`)
> **Дата отчёта:** 2026-08-02 · **Дата исправления:** 2026-08-03
> 🔴 Critical: 2 · ⚠️ Warnings: 7 · 💡 Suggestions: 8

---

Verdict: Проект в хорошем состоянии — чистая архитектура, грамотное разделение компонентов, хорошее покрытие тестами ядра. Обнаружено 2 Critical (требуют немедленного исправления), 7 Warnings и 8 Suggestions. Критические: timing-атака на сравнение токена и баг в логике важности watchdog.

Структура


NotificationWebhook/
├── app/                          # Android-клиент (Kotlin, API 34+)
│   ├── src/main/kotlin/com/notifwebhook/
│   │   ├── MainActivity.kt       (648 строк) — UI, настройки, тест webhook
│   │   ├── NotificationListenerService.kt (366 строк) — ядро: перехват, dedup, HTTP POST
│   │   ├── ForegroundKeepAliveService.kt (164 строки) — foreground-сервис
│   │   ├── BootReceiver.kt       (42 строки) — автозапуск
│   │   └── AppPrefs.kt           (186 строк) — SharedPreferences singleton
│   ├── src/test/                 — 22 + 12 unit-тестов (JUnit + Mockito)
│   └── build.gradle              — AGP 8.2.2, Kotlin 1.9.22, minSdk 34
├── server/                       # Python-бэкенд
│   ├── server.py                 (130 строк) — FastAPI приёмник, SQLite
│   ├── analyze.py                (364 строки) — CLI анализатор
│   ├── watchdog.py               (330 строк) — cron-мониторинг с ML-фильтрацией
│   └── setup.sh                  (120 строк) — systemd-установщик
├── .github/workflows/ci.yml      (251 строка) — CI/CD: lint → test → build → release
├── AGENTS.md / README.md / SKILL.md — документация
└── build.gradle / settings.gradle / gradle.properties


Всего: 5 Kotlin-файлов, 3 Python-файла, 1 shell-скрипт, 1 CI-воркфлоу, 3 тестовых файла, 3 MD-документа. 51 файл включая ресурсы и конфиги.



🔴 Critical (2)

1. Timing-атака на сравнение Bearer-токена — server.py:79

Проблема: if token != AUTH_TOKEN — оператор != для строк в Python выполняет лексикографическое сравнение с ранним выходом. Злоумышленник может побайтово подобрать токен, измеряя время ответа сервера.

Решение:
python
import hmac
if not hmac.compare_digest(token, AUTH_TOKEN):
    raise HTTPException(status_code=403, detail="Invalid auth token")


2. Баг в логике важности: medium_apps всегда возвращает False — watchdog.py:120-121

Проблема: В функции is_important:
python
default = rules.get("default_importance", "low")   # всегда "low"
...
for app in medium_apps:
    if app.lower() in app_name.lower():
        return (default == "medium" or default == "high", ...)  # всегда False!

default_importance по умолчанию "low", поэтому условие default == "medium" or default == "high" никогда не выполняется. Уведомления из Telegram/Discord/WhatsApp никогда не будут признаны важными при стандартных настройках, даже если содержат ключевые слова из include-паттернов (include-паттерны проверяются ДО этого блока, так что частично проблема смягчена — но семантически блок medium_apps мёртв).

Решение: Заменить на return (True, f"medium-app: '{app}'") или return (default != "low", ...).



⚠️ Warnings (7)

3. Утечка CoroutineScope в MainActivity — MainActivity.kt:311,459

Проблема: loadAppsAsync() и sendTestRequest() создают CoroutineScope(Dispatchers.Main) без привязки к жизненному циклу Activity. При уничтожении Activity во время выполнения корутины произойдёт:
- Попытка обновить UI мёртвой Activity → краш
- Утечка scope (корутина продолжит выполняться)

Решение: Заменить на lifecycleScope.launch (из androidx.lifecycle:lifecycle-runtime-ktx).

4. Bearer-токен в открытом виде — AppPrefs.kt:85-87

Проблема: Токен хранится в SharedPreferences без шифрования. На рутованном устройстве или через adb backup токен извлекается в открытом виде.

Решение: Использовать EncryptedSharedPreferences из AndroidX Security.

5. Нет принудительного HTTPS — MainActivity.kt:206

Проблема: Валидация URL принимает http://:
kotlin
if (!url.startsWith("http://") && !url.startsWith("https://")) {

Уведомления (включая содержимое сообщений) могут уйти открытым текстом.

Решение: Как минимум — предупреждение при вводе http:// URL. Как максимум — принимать только https://.

6. onNotificationPosted — часть pre-processing на main thread — NotificationListenerService.kt:95-180

Проблема: packageManager.getApplicationInfo() (строка 117) выполняется на главном потоке. Это I/O-операция, которая при большом потоке уведомлений может вызвать задержки и ANR.

Решение: Вынести getApplicationInfo и getApplicationLabel в serviceScope.launch или withContext(Dispatchers.IO).

7. addHistoryEntry неатомарна — AppPrefs.kt:111-119

Проблема: Read-modify-write без синхронизации. При одновременном вызове из onNotificationPosted (main thread) и sendTestRequest (main thread) гонки нет, но комментарий «Потокобезопасно» вводит в заблуждение. Если в будущем добавится фоновый поток — будет потеря данных.

Решение: Добавить @Synchronized на метод или перейти на DataStore.

8. Слишком широкий skip-паттерн "test" — watchdog.py:48

Проблема: Паттерн "test" в always_skip матчит любое уведомление, содержащее подстроку "test" (например, "latest", "testing", "protest", "test deployment failed"). Поскольку skip проверяется ДО include, уведомление "test deployment failed" будет пропущено, несмотря на наличие "deploy" и "fail" в include-паттернах.

Решение: Заменить на "test notification" (уже есть) и убрать отдельный "test". Или использовать word-boundary regex.

9. raw_data хранит полный JSON — server.py:113

Проблема: Колонка raw_data содержит полную копию JSON-тела каждого уведомления. При компрометации БД все данные уведомлений (включая содержимое сообщений) доступны в открытом виде. SQLite не шифрует данные на диске.

Решение: Как минимум — документировать риск. Как максимум — шифровать raw_data или не хранить его вовсе (все поля и так денормализованы в колонки).



💡 Suggestions (8)

10. Нет rate-limiting на /webhook — server.py

Злоумышленник может зафлудить сервер запросами, быстро заполнив SQLite. Рекомендация: добавить slowapi или middleware с ограничением по IP/токену.

11. Нет ограничения размера тела запроса — server.py

FastAPI не ограничивает размер JSON-тела. Рекомендация: добавить Request валидацию с максимальным размером (например, 64KB).

12. proguard-rules.pro пуст — app/proguard-rules.pro

Релизные сборки включают minifyEnabled true, но ProGuard-правила отсутствуют. Хотя JSONObject (Android SDK) не использует рефлексию, сторонние библиотеки (kotlinx-coroutines, Material Components) могут требовать keep-правил. Рекомендация: добавить базовые правила для корутин и Material.

13. channel_id — мёртвая проверка API level — NotificationListenerService.kt:294

kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {  // API 26
    put("channel_id", notification.channelId.orEmpty())
}

При minSdk 34 эта проверка всегда истинна. Код не багован, но создаёт ложное впечатление обратной совместимости. Рекомендация: убрать условие.

14. DB_DIR зависит от CWD — server.py:31, analyze.py:31, watchdog.py:30

python
DB_DIR = Path(os.environ.get("NOTIF_WEBHOOK_DB_DIR", str(Path.cwd() / "data")))

При прямом запуске из разных директорий путь к БД меняется. В production (systemd) это фиксировано через WorkingDirectory. Рекомендация: использовать Path(file).resolve().parent или $HOME как fallback.

15. Нет retry-логики при ошибке отправки — NotificationListenerService.kt:305-333

При недоступности сервера уведомление теряется безвозвратно. Рекомендация: добавить очередь с retry (хотя бы 2-3 попытки с экспоненциальной задержкой).

16. getHistory() парсит JSON на каждом вызове — AppPrefs.kt:100-106

Вызывается из onResume (main thread). Для 50 записей — не критично, но при росте объёма может стать узким местом. Рекомендация: кэшировать распарсенный список в памяти, инвалидировать при записи.

17. setup.sh не проверяет наличие sudo — setup.sh

Скрипт молча падает с невнятной ошибкой, если запущен без sudo. Рекомендация: добавить проверку if ! command -v sudo &>/dev/null; then ....



✅ Что сделано отлично

1. Чистая архитектура — чёткое разделение на Android-клиент, Python-сервер, CLI-анализатор и cron-монитор. Каждый компонент имеет одну ответственность.

2. Параметризованные SQL-запросы — ни одной SQL-инъекции. Все запросы используют ?-плейсхолдеры.

3. Хорошее покрытие тестами ядра — 22 теста на resolveTitle/resolveText/shouldSkipByRules/isOngoing + 12 тестов на AppPrefs. Покрыты все fallback-цепочки, edge cases (пустые/пробельные строки), JSON roundtrip, malformed JSON.

4. Грамотная обработка Android 14+ — foregroundServiceType="specialUse", POST_NOTIFICATIONS permission, RECEIVER_NOT_EXPORTED флаг, requestRebind() с повторной попыткой.

5. Дедупликация уведомлений — LinkedHashMap с LRU-вытеснением, окно 3 секунды, потокобезопасность через synchronized.

6. Watchdog с ML-подходом — система правил важности с обучением (learn), атомарный стейт-файл, JSON-режим для LLM-потребления.

7. Полноценный CI/CD — lint → test → JaCoCo → assembleDebug → assembleRelease → GitHub Release с авто-версионированием и changelog.

8. Отличная документация — README (EN/RU), AGENTS.md для AI-агентов, SKILL.md для серверной интеграции. Всё структурировано, с примерами и таблицами.

9. Правильное управление корутинами в NLS — SupervisorJob + Dispatchers.IO, явный cancel() в onDestroy.

10. InMemorySharedPreferences для тестов — чистая реализация без Android-фреймворка, позволяет тестировать AppPrefs без роботрейда.



Итог

2 Critical, 7 Warnings, 8 Suggestions.

Критические проблемы требуют немедленного внимания:
- #1 (timing-атака): исправляется одной строкой (hmac.compare_digest)
- #2 (баг watchdog): ломает всю механику важности для мессенджеров — Telegram/Discord/WhatsApp никогда не считаются важными при стандартных настройках

  ┊ 💓 preparing kanban_heartbeat…
В остальном проект демонстрирует зрелый подход: чистая архитектура, хорошие тесты, грамотная работа с Android 14+, продуманный CI/CD. Warnings в основном касаются безопасности хранения токена и отсутствия HTTPS enforcement — стандартные проблемы Android-приложений, работающих с чувствительными данными.
╰──────────────────────────────────────────────────────────────────────────────╯
  ┊ ⚡ kanban_he   0.4s

┌─ Reasoning ──────────────────────────────────────────────────────────────────┐
The review is complete. Now I need to complete the kanban task with a summary and metadata
.
└──────────────────────────────────────────────────────────────────────────────┘
  ┊ ✔
---

## Статус исправления (kanban)

Исправлены 2 critical: `server.py` — токен через `hmac.compare_digest` (403 при несовпадении); `watchdog.py` — `is_important()` реально возвращает True для medium_apps.

> Изменения внесены воркером (`t_e1f0f353`) в рабочую директорию проекта — **не закоммичены**.
