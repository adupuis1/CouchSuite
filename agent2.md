/init

# CouchSuite — Cloud Console Project Overview

We are building a **multi-tenant, cloud-scale service** aimed at production use and future commercialization. Do not optimize for single-user toy scenarios.

## Modules
- **couchserver/** — FastAPI cloud backend (Postgres/Redis in prod; SQLite only for dev). Tenancy, RBAC, catalog, ownership proofs, distributed installs, daily charts, sessions.
- **CouchLauncherFX/** — JavaFX client (controller-friendly). Org-aware auth, Home/Gaming/TV Hub, Game Page, cloud session launch.
- **sharedDirectory/** — shared artifacts (do not commit large binaries).

## Server — Must Haves
- **Tenancy**: `orgs`, `memberships`; all user-owned data is org-scoped.
- **Catalog & Proofs**: `games`, `game_external_ids`, `user_game_library` with platform proofs.
- **Distributed Installs**: `cluster_nodes`, `downloaded_games`.
- **Top 10**: `charts_top10` daily import (Steam Charts).
- **Endpoints**: `/charts/top10`, `/games/{id}`, `/users/{uid}/library`, `/auth/link/steam`, `/ownership/verify/steam`, `/sessions` (cloud) or `/play/{game_id}` (dev).

## Client — Must Haves
- **Boot flow**: Connect Controller → Select/Add User (org-aware) → Hub (Home/Gaming/TV).
- **Home**: Top 10 tiles from server.
- **Game Page**: details + **green Play** when owned + installed.
- **Play**: creates **session** in cloud mode, handles result URL/deeplink.

## Non-Functional Targets
- **Prod stack**: Postgres, Redis, S3, containers/K8s.
- **Security**: JWT, RBAC, audit logs, rate limiting, TLS.
- **Observability**: metrics/traces/logs, SLOs/alerts.
- **Scalability**: stateless API, background workers, queue for session allocation.
- **Data**: migrations, backups, GDPR/CCPA considerations.

## Execution Order
1. **Schema & migrations** (tenancy + games + installs + charts).  
2. **Charts API + daily job**.  
3. **Ownership linking & verification** (Steam first).  
4. **Node & install discovery** (dev stub; cloud allocator later).  
5. **Client**: Home Top 10 → Game Page → Play (sessions).  
6. **Ops**: containerization, CI, staging env, observability baseline.
