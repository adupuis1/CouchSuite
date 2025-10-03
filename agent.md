# CouchSuite — Snapshot

- **Mission**: Ship a production-grade, multi-tenant cloud console. Optimize for org-scale deployments, not single-user toys.
- **Modules**:
  - `couchserver/`: FastAPI backend. SQLite for dev, Postgres/Redis/S3 in prod. Owns tenancy, catalog, proofs, installs, sessions.
  - `CouchLauncherFX/`: JavaFX launcher with controller-friendly UX (connect → user select → hubs → play).
  - `sharedDirectory/`: Shared artifacts; keep binaries out of git.
- **Server Focus**: Build migrations first, import Steam Top 10 daily, support Steam ownership linking/verification, discover install nodes, expose `/charts/top10`, `/games/{id}`, `/users/{uid}/library`, `/auth/link/steam`, `/ownership/verify/steam`, `/sessions` (cloud), `/play/{game_id}` (dev). Maintain stateless API servers, queue-backed allocators, and CORS for future web tools.
- **Client Focus**: Render Top 10 tiles, show game detail with green Play when owned+installed, start cloud sessions and handle deep links, persist host + cached `/apps` for offline starts.
- **Non-Functional Targets**: Kubernetes-ready containers, JWT + RBAC + rate limits, audit trails, metrics/traces/logs with SLOs, automated backups, GDPR/CCPA hygiene. Sequence work: schema → charts → ownership → node discovery → client flows → ops hardening.

## Current Tasks
1. Persist launcher configuration (host, cached profile, token) on Raspberry Pi devices for quick boot.
2. Implement real controller detection on the Pi (gamepad/Bluetooth) and remove the manual toggle.
3. Automate Pi provisioning: package the desktop launcher, set host/org via CLI/env, install as a system service.
4. Harden CouchServer for high concurrency (`/sessions` throttling, metrics, rate limits) to support thousands of concurrent consoles.
5. Add launcher telemetry/heartbeats so the control plane can track device health and trigger upgrades.
