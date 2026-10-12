# Hosting the landing page / Размещение лендинга

This setup serves the static files in `website/` with Caddy. Caddy handles HTTPS using the Let's Encrypt ACME endpoint configured in `deploy/Caddyfile`, redirects HTTP to HTTPS, and renews certificates automatically. No custom image build, Certbot container, or certificate files in Git are needed.

## Before the first public start

No domain is configured in the repository, and no certificate has been requested. Without a `.env` file, Compose uses `localhost` as a safe local default; Caddy uses its local CA for that hostname, not Let's Encrypt. Do not treat this default as a public deployment.

1. Obtain the actual apex/second-level domain (for example, `your-domain.tld`). Do not substitute the example literally.
2. Point its DNS `A` record to the server's public IPv4 address. Add an `AAAA` record only if IPv6 reaches the same server. For the first ACME issuance, keep the record DNS-only or otherwise ensure the HTTP-01 challenge is passed through unchanged to this server.
3. Allow inbound TCP ports **80** and **443** through the provider firewall and host firewall. Port 80 must be reachable for the HTTP-01 challenge; port 443 serves HTTPS.
4. In the repository root, create an untracked `.env` file containing the assigned hostname, with no scheme or path:

   ```dotenv
   DOMAIN=your-domain.tld
   ```

   `.env` is already ignored by Git. Do not put private keys or certificate material in the repository.
5. Check the rendered Compose configuration, then start the service:

   ```sh
   docker compose config --quiet
   docker compose up -d
   docker compose logs -f web
   ```

   Caddy will request a Let's Encrypt certificate after the hostname resolves to this server and the challenge is reachable. Verify with `curl -I https://your-domain.tld/` and a browser. Do not start the service until those prerequisites are met.

Caddy persists its ACME account and certificate data in the named `caddy_data` volume and renews managed certificates automatically. Keep both `caddy_data` and `caddy_config` volumes when recreating the container. `docker compose down` stops the site but preserves them; `docker compose down -v` removes them, including certificate state.

## Перед первым публичным запуском

В этой конфигурации статические файлы из `website/` раздаёт Caddy. Он использует настроенный в `deploy/Caddyfile` ACME endpoint Let's Encrypt, автоматически перенаправляет HTTP на HTTPS и обновляет сертификаты. Сборка собственного образа, контейнер Certbot и файлы сертификатов в Git не нужны.

Домен в репозитории пока не настроен; сертификат не запрашивался. Без `.env` Compose подставляет безопасное локальное значение `localhost`: для него Caddy использует локальный CA, а не Let's Encrypt. Это значение нельзя считать настройкой публичного сайта.

1. Получите настоящий домен второго уровня (например, `your-domain.tld`). Не используйте этот пример как реальный домен.
2. Направьте DNS-запись `A` домена на публичный IPv4 сервера. Добавляйте `AAAA` только если IPv6 действительно ведёт на тот же сервер. Для первого выпуска сертификата оставьте запись в режиме DNS-only либо обеспечьте беспрепятственную передачу HTTP-01 challenge на этот сервер.
3. Разрешите входящие TCP-порты **80** и **443** в firewall провайдера и на сервере. Для HTTP-01 challenge порт 80 должен быть доступен снаружи; HTTPS обслуживается на 443.
4. В корне репозитория создайте неотслеживаемый Git файл `.env` с выданным hostname — без схемы и пути:

   ```dotenv
   DOMAIN=your-domain.tld
   ```

   `.env` уже исключён из Git. Не помещайте приватные ключи и файлы сертификатов в репозиторий.
5. Проверьте развёрнутую конфигурацию Compose и запустите сервис:

   ```sh
   docker compose config --quiet
   docker compose up -d
   docker compose logs -f web
   ```

   Caddy запросит сертификат Let's Encrypt, когда DNS будет указывать на этот сервер и challenge станет доступен. Проверьте `curl -I https://your-domain.tld/` и откройте сайт в браузере. Не запускайте сервис до выполнения этих условий.

Caddy хранит ACME account и сертификаты в именованном томе `caddy_data` и обновляет сертификаты автоматически. Сохраняйте тома `caddy_data` и `caddy_config` при пересоздании контейнера. `docker compose down` останавливает сайт, но сохраняет их; `docker compose down -v` удаляет тома вместе с состоянием сертификатов.

## Preview / Временный preview

The Cloudflare Quick Tunnel is independent of this Compose configuration. Adding these files does not start, stop, or change the existing preview.

Quick Tunnel не зависит от этой конфигурации Compose. Добавление файлов не запускает, не останавливает и не изменяет существующий preview.
