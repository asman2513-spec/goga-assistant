# Гога

Личный голосовой помощник для Android. Телефон — Honor Magic 7 Pro (Android 16). Смысл фраз разбирает облачный агент Grok Bot, между ними небольшой шлюз.

Сейчас это **этап 1**: роль помощника, окно поверх приложений, распознавание речи на телефоне и системная озвучка. Заметки, напоминания и живой Grok Bot ещё не подключены: диалог отвечает на телефоне.

## Что где лежит

- `android/` — Kotlin, Jetpack Compose, несколько модулей.
- `server/` — шлюз на Kotlin/Ktor, Docker.
- `deploy/` — прод: Caddy, Let's Encrypt, бэкап. Как поднять и перенести: [docs/deploy.md](docs/deploy.md).
- `shared/model` — общие модели для телефона и шлюза.
- `docs/` — [план v1](docs/goga-v1-plan.md), [дизайн](docs/voice-assistant-design.md), [архитектура](docs/architecture.md), [контракт с ботом](docs/bot-integration.md), [деплой](docs/deploy.md).

`minSdk` 31, потому что распознавание речи на устройстве требует API 31. `targetSdk` 36 — Android 16.

## Шлюз локально

Из корня репозитория:

```bash
docker compose up --build
```

По умолчанию поднят mock-бот: команда с телефона доходит до шлюза, шлюз сам отвечает и кладёт результат обратно. Порт опубликован только как `127.0.0.1:8080`: без файрвола шлюз не виден с других машин. Секреты в compose — заглушки для локального запуска. Для настоящего запуска скопируйте `.env.example` в `.env` и замените их. Настоящий URL бота и ключи в git не кладутся. Прод (Caddy, свой домен, бэкап): [docs/deploy.md](docs/deploy.md).

Проверка круга, когда контейнер уже отвечает:

```bash
bash server/scripts/smoke-roundtrip.sh
```

Без Docker, своими переменными:

```bash
set -a && source .env && set +a
cd server && ./gradlew run
```

Тесты шлюза: `cd server && ./gradlew test`.

Контракт, подпись и пример curl: [docs/bot-integration.md](docs/bot-integration.md). Спека API: `GET http://127.0.0.1:8080/openapi.yaml`.

### Сопряжение

Входа нет. Один раз выписывается код, телефон меняет его на долгоживущий токен. В базе хранится только хеш.

```bash
curl -sS -X POST http://127.0.0.1:8080/v1/pairing-codes \
  -H "Authorization: Bearer $GATEWAY_BOOTSTRAP_TOKEN"

curl -sS -X POST http://127.0.0.1:8080/v1/devices/pair \
  -H "Content-Type: application/json" \
  -d '{"code":"КОД","device_name":"Honor Magic 7 Pro"}'
```

Дальше телефон шлёт `Authorization: Bearer <device_token>`.

Отозвать устройство (токен сразу перестаёт работать; повтор того же запроса тоже `204`):

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE \
  "http://127.0.0.1:8080/v1/devices/$DEVICE_ID" \
  -H "Authorization: Bearer $GATEWAY_BOOTSTRAP_TOKEN"
```

База по умолчанию SQLite в томе Docker. PostgreSQL — другой `DATABASE_URL` (`jdbc:postgresql://...`) и `DATABASE_USER` / `DATABASE_PASSWORD`. Отдельный облачный сервис не требуется.

## APK

Нужны JDK 17 и Android SDK 36.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest :core:model:test
```

Отладочный APK: `android/app/build/outputs/apk/debug/app-debug.apk`. В CI тот же файл публикуется артефактом `goga-debug-apk`. Firebase не подключён, `google-services.json` не нужен. Поставить можно напрямую, без магазина.

На телефоне: «Поговорить» на вкладке «Сегодня», плитка «Гога» в шторке или долгое нажатие питания после назначения помощником по умолчанию. На Honor: Настройки → Приложения → Приложения по умолчанию → Цифровой помощник → Гога. Микрофон — русский, ответ можно набрать текстом. Фраза «Окей, Гога» не используется.

## Чего на этапе 1 нет

Будильники, настоящие push, утренняя сводка, задачи и календарь как действия помощника, живой разбор фразы ботом. Заметки на шлюзе по-прежнему только через API. Фраза «Окей, Гога» — после v1.
