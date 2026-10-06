# Гога

Личный голосовой помощник для Android. Телефон — Honor Magic 7 Pro (Android 16). Смысл фраз разбирает облачный агент Grok Bot, между ними небольшой шлюз.

Сейчас это **этап 0**: каркас приложения, шлюз и проверенный обмен с ботом. Самого помощника ещё нет.

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

Отладочный APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Firebase не подключён, `google-services.json` не нужен. Поставить можно напрямую, без магазина.

Вкладки: «Сегодня», «Задачи», «Заметки», «Настройки». Это заглушки.

## Чего на этапе 0 нет

Роль помощника вместо Google, окно поверх приложений, распознавание и озвучка, будильники, настоящие push, утренняя сводка по расписанию, задачи и календарь как свои таблицы на шлюзе. Заметки на шлюзе уже создаются и правятся. Фраза «Окей, Гога» — после v1.
