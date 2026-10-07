# Архитектура

Гога — личный голосовой помощник на Android. Телефон принимает речь и показывает списки. Смысл фразы позже разбирает Grok Bot. Между ними стоит шлюз из этого репозитория.

Этап 0 оставил каркас и круг «команда телефона → очередь → бот → результат → телефон». Этап 1 добавляет роль помощника, оверлей, распознавание и синтез. Диалог этого этапа отвечает локально и не требует живого бота. Будильников и настоящей отправки push по-прежнему нет.

## Репозиторий

Два независимых Gradle-проекта, общая модель:

- `shared/model` — Kotlin JVM, DTO и интерфейсы. Его подключают и `server/`, и `android/`.
- `server/` — шлюз Ktor. Образ Docker собирается только из него и не требует Android SDK.
- `android/` — приложение. `./gradlew assembleDebug` запускается из этого каталога.
- `docs/` — план v1, полный дизайн, этот файл, ADR и контракт с ботом.

Один Gradle на весь репозиторий заставил бы Docker-сборку конфигурировать Android-плагин. Поэтому сборки разделены, а версии лежат в одном `gradle/libs.versions.toml`.

## Модули Android

Зависимости только «вниз». `feature:notes`, `feature:tasks`, `feature:calendar` и `feature:assistant` друг на друга не ссылаются. Напоминание может хранить `task_id` как строку — это слабая связь, не зависимость модулей.

| Модуль | Зачем |
|---|---|
| `:app` | Compose, нижние вкладки «Сегодня», «Задачи», «Заметки», «Настройки» |
| `:core:model` | Общие модели и контракт |
| `:core:data` | Room: заметки, задачи, напоминания, очередь неотправленных команд |
| `:core:network` | Клиент шлюза и заготовки push-каналов |
| `:feature:notes` | Экран заметок |
| `:feature:tasks` | Экран задач и `ReminderChannel` |
| `:feature:calendar` | Блок календаря на «Сегодня» и `CalendarProvider` |
| `:feature:assistant` | `VoiceInteractionService`, служба распознавания для списка помощников, оверлей, on-device STT (ru-RU), системный TTS, локальный диалог |

`ReminderChannel`: сейчас задуманы `local_alarm` (v1, будильник телефона), `push`, `sms`, `call`. Реализаций доставки нет.

`CalendarProvider`: `google-via-bot` (v1, коннектор бота, у телефона нет токена Google), `caldav`, `android-calendar`.

`PushChannel`: `fcm`, `rustore`, `websocket`. Firebase не подключён, `google-services.json` не нужен.

База на телефоне создаётся при старте. `fallbackToDestructiveMigration` допустим, пока в ней нет данных пользователя. Перед реальными заметками его заменят миграции. Схема Room выгружается в `android/core/data/schemas`.

## Шлюз

Kotlin, Ktor, JDBC. По умолчанию SQLite (`DATABASE_URL=jdbc:sqlite:...`). PostgreSQL — тот же код и драйвер `org.postgresql`, если URL начинается с `jdbc:postgresql:`. В CI гоняется SQLite. SQL общий: текстовые UUID, `ON CONFLICT`, без типов только SQLite.

Один пользователь. При первом запуске создаётся строка `users` с UUID. `SINGLE_USER_ID` учитывается только если таблица пуста. У заметок, команд и устройств есть `user_id`, `created_at`, `updated_at`. Токен устройства и код сопряжения в базе лежат как SHA-256. `DELETE /v1/devices/{id}` с bootstrap-токеном ставит `revoked_at`: строка остаётся, токен перестаёт работать.

Локальный `docker-compose.yml` публикует порт только на `127.0.0.1:8080`. Прод — `deploy/` (Caddy, Let's Encrypt, шлюз без опубликованного порта): [deploy.md](deploy.md).

Очередь — таблица `commands`. Воркер забирает `queued`, ставит `dispatched` и вызывает `BotBridge`. `WebhookBotBridge` шлёт HTTP. `MockBotBridge` подписывает ответ и бьёт в живой `POST /v1/bot/results`, поэтому тест видит тот же путь, что и настоящий бот.

Контракт: [bot-integration.md](bot-integration.md). OpenAPI: `GET /openapi.yaml`.

## SDK

`minSdk` 31: встроенный `SpeechRecognizer` на устройстве требует API 31, он нужен уже в следующем этапе. `targetSdk` и `compileSdk` — 36, это Android 16 на Honor Magic 7 Pro. AGP 8.13.2, а не 9.x: свежий Compose BOM и OkHttp 5.5 требуют compileSdk 37. Для цели «Android 16» взят BOM `2026.06.01` (Compose UI 1.11), а HTTP-клиент шлюза на телефоне — движок CIO, без OkHttp. Когда `compileSdk` поднимут, клиент можно перевести на OkHttp.
