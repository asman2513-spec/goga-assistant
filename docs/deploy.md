# Деплой и перенос шлюза Гоги

Прод — два контейнера из `deploy/docker-compose.prod.yml`: `gateway` (Ktor, SQLite) и `caddy` (HTTPS, сертификат Let's Encrypt). Порты наружу публикует только Caddy. Шлюз доступен лишь во внутренней сети Docker, поэтому обход ufw со стороны Docker его не открывает.

Команды ниже выполняются из `deploy/`. На сервере это обычно `/opt/goga/deploy` после `git clone` в `/opt/goga`.

| Что | Где (относительно `deploy/`) |
|---|---|
| Секреты и настройки | `.env` (mode 600, root) |
| База SQLite (пользователь, устройства, команды, заметки) | `data/gateway/goga.db` (+ `-wal`, `-shm`) |
| Сертификаты и ключи ACME | `data/caddy/` (необязательно, Caddy получит новые) |
| Файлы деплоя | `docker-compose.prod.yml`, `Caddyfile`, `backup.sh`, `.env.production.example` |

Секреты (`GATEWAY_BOOTSTRAP_TOKEN`, `BOT_INBOUND_SECRET`, `BOT_WEBHOOK_AUTH_VALUE`) нельзя коммитить, печатать или вставлять в чаты. Переносите их только внутри `.env` или архива бэкапа.

Локальный `docker-compose.yml` в корне репозитория — не этот стек. Он публикует шлюз только на `127.0.0.1:8080`, чтобы запуск без файрвола не открыл порт в сеть. В проде его не используют.

## 1. Требования к серверу

- Linux x86_64 или arm64, ≥ 1 ГБ RAM (для сборки образа Gradle лучше ≥ 2 ГБ RAM+swap; при нехватке: `fallocate -l 2G /swapfile2 && chmod 600 /swapfile2 && mkswap /swapfile2 && swapon /swapfile2 && echo '/swapfile2 none swap sw 0 0' >> /etc/fstab`).
- Docker Engine и Compose v2 (`docker compose version`). Ubuntu: `apt install docker.io docker-compose-v2`.
- `git`, `curl`, `openssl`.
- Свободны TCP-порт 80 (обязательно: ACME HTTP-01 и редирект) и порт HTTPS (`GOGA_HTTPS_PORT`, лучше 443; если 443 занят другим сервисом, например VPN, — любой другой, например 4443). Проверка: `ss -tlnp | grep -E ':(80|443|4443)\s'`.
- DNS-имя, указывающее на IP сервера. Без домена подходит `<ip-через-дефисы>.sslip.io` (например, `203-0-113-10.sslip.io` → 203.0.113.10).

## 2. Первый запуск

```bash
git clone https://github.com/asman2513-spec/goga-assistant.git /opt/goga
cd /opt/goga/deploy
umask 077
cp .env.production.example .env
chmod 600 .env
# два секрета: openssl rand -hex 32
# заполнить GOGA_DOMAIN, GOGA_HTTPS_PORT, GATEWAY_PUBLIC_URL
mkdir -p data/gateway && chown 10001:10001 data/gateway
docker compose -f docker-compose.prod.yml up -d --build
```

`10001` — uid пользователя `goga` внутри образа. Дальше — разделы «Запуск» и «Проверка».

## 3. Бэкап

Из `deploy/` на работающем сервере:

```bash
cd /opt/goga/deploy
./backup.sh            # архив в /var/backups/goga/goga-backup-YYYYmmdd-HHMMSS.tar.gz (mode 600)
                       # + .commit с SHA задеплоенного коммита; каталог: аргумент или GOGA_BACKUP_DIR
```

Скрипт на несколько секунд останавливает `gateway`, чтобы файлы SQLite были согласованы, и запускает его обратно. Caddy не останавливается. Команды, пришедшие в эти секунды, телефон повторит сам (тот же `idempotency_key`). Для окончательного переноса лучше сначала остановить приём: `docker compose -f docker-compose.prod.yml stop gateway`, затем `GOGA_BACKUP_HOT=1 ./backup.sh`.

Каталог по умолчанию `/var/backups/goga` лежит вне git, чтобы архив с секретами не попал в коммит.

Скопировать архив на новый сервер (не через чат и не через публичные хранилища):

```bash
scp /var/backups/goga/goga-backup-*.tar.gz root@NEW_HOST:/root/
```

## 4. Восстановление и перенос

```bash
git clone https://github.com/asman2513-spec/goga-assistant.git /opt/goga
cd /opt/goga && git checkout <тот же коммит или новее>
cd /opt/goga/deploy
umask 077
tar -xzf /root/goga-backup-*.tar.gz -C /opt/goga/deploy   # .env, data/, compose, Caddyfile
chown root:root .env && chmod 600 .env
chown -R 10001:10001 data/gateway                         # uid пользователя goga в образе
```

Файлы деплоя уже есть в репозитории. Из архива берите `.env` и `data/`. Распакованные `docker-compose.prod.yml`, `Caddyfile` и `backup.sh` можно заменить версиями из git, если коммит новее бэкапа.

## 5. Смена имени и адреса

Отредактируйте `deploy/.env`:

```dotenv
GOGA_DOMAIN=NEW-IP-WITH-DASHES.sslip.io      # или ваш домен
GOGA_HTTPS_PORT=443                          # или 4443, если 443 занят
GATEWAY_PUBLIC_URL=https://NEW-IP-WITH-DASHES.sslip.io   # с ":порт", если он не 443
```

Каталог `data/caddy/` со старыми сертификатами можно удалить: для нового имени Caddy выпустит новый сертификат сам. Лимит Let's Encrypt — 5 одинаковых сертификатов в неделю, не пересоздавайте контейнер в цикле с очищенным `data/caddy`.

### На что влияет `GATEWAY_PUBLIC_URL`

- **Grok Bot.** Шлюз передаёт боту callback `GATEWAY_PUBLIC_URL + /v1/bot/results` в каждой исходящей команде, а рутина бота может хранить адрес шлюза у себя (например, `GOGA_GATEWAY_URL`) для незапрошенных событий (утренняя сводка). После переноса обновите адрес у бота. `BOT_INBOUND_SECRET` можно оставить прежним (он в `.env`); если меняете — меняйте одновременно на шлюзе и у бота, иначе подписи HMAC не пройдут (401).
- **Телефон.** Приложение ходит на базовый URL шлюза, указанный в настройках. Новый адрес нужно ввести в приложении. Если база перенесена из бэкапа, токены устройств остаются действительными и повторная привязка не нужна. Если база новая — привяжите телефон заново: выпустить код (`POST /v1/pairing-codes` с `Authorization: Bearer $GATEWAY_BOOTSTRAP_TOKEN`) и ввести его в приложении. Отозвать старый токен: `DELETE /v1/devices/{id}` с тем же bootstrap-токеном.
- **Webhook (`BOT_BRIDGE=webhook`).** `BOT_WEBHOOK_URL` и `BOT_WEBHOOK_AUTH_VALUE` от сервера не зависят, но новый сервер должен иметь исходящий HTTPS-доступ к webhook-адресу.

## 6. Запуск

```bash
cd /opt/goga/deploy
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps        # gateway: healthy, caddy: up
docker compose -f docker-compose.prod.yml logs caddy | grep -i "certificate obtained"
```

## 7. Файрвол

На исходном сервере host-файрвол (ufw) не включён — по решению владельца, чтобы не задеть VPN-сервисы на том же сервере. Шлюз закрыт тем, что контейнер `gateway` не публикует порт на хосте (только `expose` во внутренней сети Docker). Не добавляйте `ports:` сервису `gateway`.

Корневой `docker-compose.yml` публикует `127.0.0.1:8080:8080`. С других машин этот порт не открывается, но файла всё равно недостаточно для прода: нет Caddy и Let's Encrypt. После любого изменения compose проверяйте снаружи: `nc -zv -w5 <IP> 8080` должен не подключаться.

Порты, опубликованные Docker (80 и HTTPS-порт Caddy), обходят ufw в любом случае.

Если на новом сервере решите включить ufw: сначала посмотрите все слушающие порты (`ss -tulpn`) и разрешите те, что нужны другим сервисам, иначе вы их отрежете.

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp                      # ПЕРВЫМ, иначе потеряете SSH
ufw allow 80/tcp
ufw allow ${GOGA_HTTPS_PORT}/tcp      # 443 или 4443
# + порты других сервисов
systemd-run --on-active=180 --unit=ufw-deadman ufw disable   # страховка
ufw --force enable
# проверить с другой машины: ssh, https, другие сервисы; затем:
systemctl stop ufw-deadman.timer
```

## 8. Проверка

С внешней машины:

```bash
URL=https://NEW-IP-WITH-DASHES.sslip.io[:PORT]
curl -fsS "$URL/health"                 # {"status":"ok",...,"bridge":"mock"|"webhook"}
curl -vI "$URL/health" 2>&1 | grep -E "issuer|expire"    # Let's Encrypt
nc -zv -w5 NEW_IP 8080                  # должен НЕ подключаться
```

Сквозная проверка (создаёт тестовое устройство «smoke» и заметку):

```bash
cd /opt/goga
read -rs GATEWAY_BOOTSTRAP_TOKEN; read -rs BOT_INBOUND_SECRET; export GATEWAY_BOOTSTRAP_TOKEN BOT_INBOUND_SECRET
GATEWAY_URL="$URL" server/scripts/smoke-roundtrip.sh
```

Затем: телефон открывает заметки/команды, бот отправляет тестовое событие (`server/scripts/post-bot-result.sh`).

## 9. Откат

- Пока старый сервер не выключен, откат = вернуть старый адрес в приложении и у бота; старый шлюз продолжает работать со своей базой. (Если старый `gateway` был остановлен — `docker compose -f docker-compose.prod.yml start gateway` в `deploy/` на старом сервере.)
- Команды и заметки, созданные на новом сервере после переключения, на старом не появятся. Чтобы вернуть их, сделайте `./backup.sh` на новом сервере и распакуйте `data/gateway` на старом (при остановленном `gateway`).
- Не удаляйте старый сервер и архивы бэкапа, пока новый не проработал несколько дней.
