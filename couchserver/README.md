# CouchServer — Multi-Tenant Cloud Console (FastAPI + Postgres/SQLite + Redis)

CouchServer is the backend of **CouchSuite**, a **multi-tenant cloud console** that manages game catalogs, user libraries, distributed installs, and cloud play sessions.  
This project is designed to scale from local development (SQLite) to production (Postgres, Redis, object storage, Kubernetes).  

> ⚠️ This is **not a personal toy server**. It is the foundation of a **full-fledged business-grade cloud service**.

---

## `CouchLauncherFX/LAUNCHERGUIAGENT.md`

```markdown
# Goal

Build a runnable JavaFX client (CouchLauncherFX) that connects to the **multi-tenant CouchServer**.
On boot → Connect Controller → Select/Add User (org-aware) → Hub (Home/Gaming/TV).
New machines go straight to Home after first user creation.

## Cloud awareness

- The launcher must authenticate to the **cloud server** and operate per **organization (tenant)**.
- If the logged-in user has memberships in multiple orgs, prompt for org selection before entering the Hub.
- Base API URL is configurable (env flag or config file); no hard-coding.

## Inputs (assets)

Use images from:
`CouchSuite/CouchSuiteLauncherClientDesignReference/Launcher layout/`

- `connect controller.png` (connect/controller)
- `select user.png` (select/user)
- `home.png` (home)
- `gaming.png` (gaming)
- `tv.png` (tv)

Copy at runtime to `/launcher_layout`.

### Icons (override first)

Always pull icons from:
`/CouchLauncherFX/resources/app/assets/`
- If both an icon and a layout image exist, prefer the **icon**.
- Header (all tabs): **user**, **controller**, **wifi**.
  - User → profile/switch user/org
  - Controller → settings/connect
  - Wi-Fi → reflects connectivity (no action yet)

## Screen flow (state machine)

BOOT
└─ CONNECT_CONTROLLER (continue on input)
└─ SELECT_USER (shows “+” if none; org selection if multiple)
└─ HUB (tabs: Home | Gaming | TV; default = Home)


## Game Integration (cloud-first)

- **Home** calls `GET /charts/top10` and renders tiles.
- Selecting a tile opens **Game Page**:
  - Show cover, name, description, rating, platforms.
  - Fetch `/users/{uid}/library` to determine **ownership** (org-scoped).
  - Ask server if a **ready installation** exists for this org (and region).
  - If owned **and** an install exists → show **green Play**.

### Play action
- **Cloud mode:** `POST /sessions` with `{ org_id, user_id, game_id }`
  - Server allocates a node and returns a session descriptor (e.g., stream URL/deeplink).
  - Launcher opens the returned URL or starts the streaming client.
- **Dev/local mode:** `POST /play/{game_id}` may return a local exec path or store deep-link.

## UX/Engineering Notes

- Controller and keyboard navigation must be smooth.
- UI should cope with API failures: show retry button and small status toasts.
- Store config at `~/.config/couchlauncherfx/config.json` (API base URL, last org, last user).
- Keep Home/Gaming/TV visuals consistent; prefer icons over layout images when present.
```

## Quick start (development)

```bash
cd couchserver
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Dev mode: runs with SQLite
uvicorn server:app --reload --host 0.0.0.0 --port 8080

Smoke tests:

curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/version
curl http://127.0.0.1:8080/apps
```

```
    Dev DB: couch.db (SQLite) auto-created beside server.py.

    Prod DB: Postgres (DATABASE_URL env).

    Secrets: COUCHSUITE_USERNAME_SECRET (dev: .env file; prod: secrets manager).
```

API Overview
Core

```
    GET /health → readiness probe

    GET /version → { "server": "0.1.0" }

    GET /apps?enabled=true|false → default repository tiles

    POST /apps, PUT /apps/{id}, DELETE /apps/{id} → manage repo entries

    CORS enabled for future web tooling
```

Users & Tenancy

```
    POST /users → register user (username + password)

    POST /auth/login → login, returns { user_id, orgs, token }

    GET /users/exists → { has_users: bool } (bootstrap)

    GET /orgs → list organizations (admin)

    POST /orgs → create org (admin)

    GET /orgs/{org_id}/members → list members

    POST /orgs/{org_id}/members → add member

    GET /users/{user_id}/settings / PUT ... → JSON settings blob
```

Game Catalog & Ownership

```
    GET /games/{id} → full game info (cover, desc, rating, external IDs)

    GET /users/{uid}/library → user’s owned games (with proofs)

    POST /auth/link/steam → link Steam account (OID/SteamID)

    POST /ownership/verify/steam → fetch Steam library → populate user_game_library
```

Charts & Discovery

```
    GET /charts/top10?date=YYYY-MM-DD → Top 10 games (daily snapshot from Steam Charts)
```

Sessions & Play

```
    POST /play/{game_id} → dev/local mode: returns local executable/deep link

    POST /sessions → cloud mode: allocates a cluster node, validates ownership/install, returns session descriptor (e.g. stream URL)

    GET /sessions/{id} → query session status

    DELETE /sessions/{id} → end session
```

Database Schema

SQLite/Postgres-compatible; add to seed.sql or use migrations.
Tenancy & Accounts

```
CREATE TABLE orgs (
  id INTEGER PRIMARY KEY,
  slug TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  pass_hash TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE memberships (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL DEFAULT 'member',
  UNIQUE (org_id, user_id)
);

CREATE TABLE user_accounts (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform TEXT NOT NULL,
  account_id TEXT NOT NULL,
  display_name TEXT,
  metadata_json TEXT,
  UNIQUE (org_id, user_id, platform)
);
```

Game Catalog & Proofs

```
CREATE TABLE games (
  id INTEGER PRIMARY KEY,
  slug TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  rating REAL,
  cover_url TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE game_external_ids (
  id INTEGER PRIMARY KEY,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  platform TEXT NOT NULL,
  external_id TEXT NOT NULL,
  UNIQUE (game_id, platform)
);

CREATE TABLE user_game_library (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  platform TEXT,
  external_id TEXT,
  ownership_source TEXT NOT NULL,
  proof_type TEXT,
  proof_value TEXT,
  verified_at TIMESTAMP,
  UNIQUE (org_id, user_id, game_id)
);
```

Cluster & Installs

```
CREATE TABLE cluster_nodes (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  region TEXT,
  capacity INTEGER,
  status TEXT DEFAULT 'active',
  UNIQUE (org_id, name)
);

CREATE TABLE downloaded_games (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
  node_id INTEGER NOT NULL REFERENCES cluster_nodes(id) ON DELETE CASCADE,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  install_path TEXT NOT NULL,
  launcher TEXT,
  executable TEXT,
  last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (org_id, node_id, game_id)
);
```

Charts & Ratings

```
CREATE TABLE charts_top10 (
  id INTEGER PRIMARY KEY,
  chart_date DATE NOT NULL,
  rank INTEGER NOT NULL CHECK(rank BETWEEN 1 AND 10),
  name TEXT NOT NULL,
  steam_appid INTEGER,
  game_id INTEGER REFERENCES games(id) ON DELETE SET NULL,
  source TEXT NOT NULL DEFAULT 'steamcharts',
  UNIQUE (chart_date, rank)
);
CREATE INDEX idx_charts_date ON charts_top10(chart_date);

CREATE TABLE game_ratings (
  id INTEGER PRIMARY KEY,
  game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  source TEXT,
  score REAL,
  details TEXT
);
```

Ownership Proofs

```
    Steam: GetOwnedGames → proof JSON {appid, playtime, timestamp}.

    Microsoft/Xbox: ProductId/entitlementId (partner APIs).

    EA: local manifests/license metadata (hashes, paths).

    Epic/GOG: manifest metadata + executable hash.

    Manual: receipt JSON (sanitized).
```

Proof JSON Example:

```
{
  "path": "/games/xyz/bin/game.exe",
  "sha256": "abcd1234...",
  "size": 123456789,
  "mtime": 1720000000
}
```

Daily Top 10 (Steam Charts)

```
    Cron/APScheduler task scrapes Top 10 daily.

    Maps steam_appid → game_external_ids.

    Populates charts_top10.

    Served at /charts/top10.
```

Production Notes

```
    DB: Postgres + migrations.

    Cache: Redis for hot lists, sessions, rate-limits.

    Storage: Object store for covers/assets.

    Auth: org-scoped JWTs, RBAC.

    Sessions: allocator service + queue.

    Observability: metrics, traces, logs.

    Security: TLS, audit logs, secrets manager.

    Ops: containers, K8s, IaC, CI/CD.
```
