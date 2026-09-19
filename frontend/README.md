# GeoRail PackWiz — legacy pack site

> **Legacy.** The mod no longer downloads from this server. It now talks to the
> **GeoPak pack website** (`GeoRail-PackWeb`): it lists packs via `GET /api/packs`
> and downloads them as one AES-256-GCM encrypted `.geopak` via
> `GET /request-pack` (see that repo's `MOD_INTEGRATION.md`). This folder is kept
> for the landing page / manual download only.

The old packwiz-style protocol endpoints (`/metadata.json`, `/dist/...`,
`/client_config.json`) are no longer used by the mod and are kept for reference.

## What the server exposes

| URL | Purpose |
|---|---|
| `/` | Landing page (Vite build) |
| `/metadata.json` | Pack manifest for the mod (SHA-1 per file, protocol v1) |
| `/dist/<path>` | Individual pack files, e.g. `/dist/pack.mcmeta` |
| `/pack/latest.zip` | Whole pack as a zip (for manual install) |
| `/client_config.json` | Mod remote config — pushes the source list to clients |
| `/icon.png` | Pack icon (favicon) |

The mod's `baseUrl` is `https://packwiz.justnoone.eu` — it fetches
`{baseUrl}/metadata.json` and `{baseUrl}/dist/...`.

## Local development

```bash
npm install
npm run dev        # Vite dev server only (page hot-reload, no pack API)
npm run serve      # build + run the full server on http://localhost:8080
```

The server reads pack files from `pack/` (replace the sample content with
your real pack). `metadata.json` is regenerated automatically when files
change — no restart needed.

### Environment variables

| Var | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | Listen port |
| `PACK_DIR` | `pack` | Directory served as the pack |
| `CLIENT_VERSION` | `2.4.0` | Must match the mod version; clients older than this are refused |
| `SOURCE_NAME` | `GeoRail` | Source name shown in the mod's config screen |
| `BASE_URL` | auto | Override the baseUrl advertised to the mod (use behind proxies) |
| `SSL_CERT` / `SSL_KEY` | — | PEM paths; when both are set the server serves HTTPS directly |

## Deploying to the VPS (87.106.147.174)

### 1. DNS

```
packwiz.justnoone.eu  A  87.106.147.174
```

### 2. Get the code on the server

```bash
# install Node 20+ (Debian/Ubuntu):
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

sudo mkdir -p /opt/geopackwiz-site
sudo rsync -a --exclude node_modules --exclude dist ./ /opt/geopackwiz-site/
cd /opt/geopackwiz-site
npm ci --omit=dev
npm run build
```

Put your real pack files into `/opt/geopackwiz-site/pack/`.

### 3. TLS (Let's Encrypt) — do this after DNS has propagated

```bash
sudo apt-get install -y certbot
sudo certbot certonly --standalone -d packwiz.justnoone.eu
```

Renewal is automatic via a systemd timer, or add this to cron:

```
17 3 * * * certbot renew --quiet --deploy-hook "systemctl restart geopackwiz"
```

### 4. Run it (systemd)

`/etc/systemd/system/geopackwiz.service`:

```ini
[Unit]
Description=GeoRail PackWiz site
After=network.target

[Service]
WorkingDirectory=/opt/geopackwiz-site
ExecStart=/usr/bin/node server.mjs
Environment=PORT=443
Environment=SSL_CERT=/etc/letsencrypt/live/packwiz.justnoone.eu/fullchain.pem
Environment=SSL_KEY=/etc/letsencrypt/live/packwiz.justnoone.eu/privkey.pem
Environment=BASE_URL=https://packwiz.justnoone.eu
Restart=always
User=www-data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now geopackwiz
```

> Port 443 needs `CAP_NET_BIND_SERVICE` or a root user, since it's below 1024.
> If you'd rather keep it simple, run on `PORT=8080` and let nginx terminate
> TLS and proxy to it — then no `Environment=SSL_*` lines are needed and
> certbot's nginx plugin handles everything:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name packwiz.justnoone.eu;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name packwiz.justnoone.eu;

    ssl_certificate     /etc/letsencrypt/live/packwiz.justnoone.eu/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/packwiz.justnoone.eu/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
sudo certbot --nginx -d packwiz.justnoone.eu
```

### 5. Firewall

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 25565/tcp   # Minecraft server, if it runs on this box
```

## How the client uses the GeoPak website

Every connection setting is **baked into the mod jar** at build time
(`ServerConfig.java` — generated from the site's `.env` before compiling, stored
XOR-masked so the values don't sit as plain text):

- `WEBSITE_BASE_URL` → `https://grpu.justnoone.eu`
- `REQUEST_PATH` → `/api/request-pack` (auto-falls back to `/request-pack`)
- `API_KEY` / `SECRET_KEY` → the site's `MOD_API_KEY` / `GEOPAK_SECRET_KEY`
- `ENABLED_SERVER_ADDRESS` → `play.georail.eu` (the pack is only applied there)

Players do not configure any of this — a player config only stores which packs
are selected (in-game: Options → **GeoRail Packs...** or `K`):

```json
{
  "websitePacks": [],
  "lastSyncTime": 0
}
```

`websitePacks` holds pack UUIDs; empty = every pack the key can see. Packs are
**applied only on `play.georail.eu`**: they are fully loaded into the initial
game load so nothing is missing, and they are switched off before the world
loads on any other server or in singleplayer.

The mod syncs once during game startup (before the initial resource-pack load)
and keeps the packs in RAM. Joining `play.georail.eu` does **zero** join-time
work — the pack is already loaded and enabled, so it is never downloaded,
decrypted or re-applied a second time. Each pack is cached by its website
sha256, so a sync downloads **only the packs that are new or updated**, several
at the same time (up to 3 parallel transfers), and every pack is verified
against its sha256 after decryption. If a pack cannot be reached, any older
cached copy is used instead; with no internet at all the game starts normally
and the sync can be retried from the config screen (Options → **GeoRail
Packs...** or `K`).

## Updating the pack

Upload the new zip to the GeoPak website (GeoRail-PackWeb). The next time a
client syncs, that pack's sha256 changed so the mod downloads exactly that pack
again — all unchanged packs are served from the local encrypted cache.

## Security notes

- The mod uses the **default verified TLS trust store** and **never follows
  redirects**, so the `X-API-Key` header can only ever reach the configured host.
- Pack data is protected by AES-256-GCM (authenticated) and a per-pack sha256
  check — tampered or swapped packs are rejected at load time.
- **Packs are served from RAM only.** The zips are decrypted straight into
  memory and never written to the game folder, so players cannot pull the
  GeoRail assets out of their `resourcepacks` directory.
- The only on-disk artifact is an encrypted cache (`.geopak-cache/<pack-uuid>.gpkc`)
  holding the raw AES-encrypted downloads, one file per pack, so repeat launches
  and unchanged packs never re-download; it is unusable without the key baked
  into the jar.
- To ship a new audience a jar with different credentials, regenerate
  `ServerConfig.java` from the new `.env` and rebuild.
