# Repository Guidelines

## Project Structure & Module Organization
CouchSuite is split into three top-level modules:
- `CouchLauncherFX/` — Gradle-based JavaFX client. Primary code in `src/main/java/app/` mirrored by resources in `src/main/resources/`.
- `couchserver/` — FastAPI microservice backed by `couch.db` seeded via `seed.sql`.
- `sharedDirectory/` — host share used for exchanging artifacts; keep large binaries or runtime exports here rather than in source.
Keep new Java packages under `app.*` and mirror that path in `src/test/java/app/`. Place server helpers beside `server.py` or in a `services/` subpackage to keep imports on the FastAPI path.

## Build, Test, and Development Commands
- `./gradlew run` (from `CouchLauncherFX/`) launches the JavaFX desktop client.
- `./gradlew clean build` compiles and packages the client; run before opening a PR.
- `./gradlew test` executes the JUnit suite.
- `python3 -m venv .venv && source .venv/bin/activate` (inside `couchserver/`) prepares an isolated environment.
- `pip install -r requirements.txt` installs server dependencies; rerun after requirements changes.
- `uvicorn server:app --reload --host 0.0.0.0 --port 8080` starts the API for local pairing with the launcher.

## Coding Style & Naming Conventions
Use 4-space indentation across Java and Python. Java classes and enums are `PascalCase`, methods/fields `camelCase`, and constants `UPPER_SNAKE_CASE`; group related controllers or services into dedicated classes instead of growing `Main`. Avoid wildcard imports and keep resource names aligned with the corresponding view/controller (`LauncherView.fxml` → `LauncherViewController`). Python modules follow `snake_case`, endpoint functions remain short, and response models live next to their Pydantic definitions. Externalize user-facing strings and keep CSS under `src/main/resources/`.

## Testing Guidelines
Add JUnit 5 tests in `src/test/java/app/` mirroring package names; prefer `LauncherServiceTest`-style class names and descriptive test methods. Run `./gradlew test` before commits and whenever you touch process-launch or JSON parsing logic. For the API, create a `couchserver/tests/` package, use `pytest` with FastAPI's `TestClient`, and seed temporary databases with a copy of `seed.sql`. Provide coverage for CRUD handlers and error paths; attach manual `./gradlew run` and `curl /apps` smoke results to PRs affecting integration points.

## Commit & Pull Request Guidelines
The snapshot lacks prior git history, so adopt short, imperative subjects (`Add tile filter predicate`) with bodies explaining the motivation and side effects. Keep commits scoped to one module when possible, or state cross-module impacts explicitly. PRs should include: purpose summary, testing notes (commands run + results), screenshots for UI tweaks, and database migration instructions when touching `seed.sql` or schema files. Link issues or tasks in the description and request reviews from both client and server maintainers when changes span directories.

## Task Queue (execute in order)
T1 — Verify current state
- Confirm `couchserver/` has: `server.py`, `requirements.txt`, `seed.sql`.
- Confirm `CouchLauncherFX/` has: `build.gradle`, `settings.gradle`, `src/main/java/app/` with `Main.java`, `HttpRepo.java`, `AppTile.java`.
- Build the client: `./gradlew clean build` → must pass.

T2 — Client: resilient fetch + “Retry” button
- Add a small fetch wrapper with 1–3 retries (250–500ms backoff) for `/apps`.
- On failure: show a centered “Server unavailable” message and a Retry button that re-queries.
- Keep logs minimal (print stacktrace to console, not modal).

T3 — Client: offline cache of `/apps`
- Save last good `/apps` JSON to `~/.config/couchlauncherfx/apps_cache.json`.
- On startup: try live fetch (short timeout). If it fails, load from cache (if present) and show a subtle “offline” badge.
- Provide a “Refresh” button somewhere (toolbar or settings) to force re-fetch.

T4 — Client: Settings persistence for Host
- Save Host to `~/.config/couchlauncherfx/config.json` on successful launch click.
- Load the Host on app start and prefill the field.
- Keep it dependency-free (use `java.nio.file` and a tiny JSON string).

T5 — Client: Linux launch path (Moonlight)
- Detect OS: macOS → keep `open -a TextEdit`. Linux → run `flatpak run com.moonlight_stream.Moonlight stream <HOST> --app "<APP>"`.
- Add optional env flag `DEV_FORCE_STUB=1` to force stub on Linux (for CI).

T6 — Server: launchd auto-restart (mac)
- Create `~/bin/run-couchserver.sh` and `~/Library/LaunchAgents/com.couch.server.plist` with `RunAtLoad=true`, `KeepAlive=true`, logs under `~/Library/Logs/`.
- Document `launchctl load/start/stop/unload` commands in `couchserver/README.md`.

T7 — API niceties
- Add `GET /version` returning `{ "server":"0.1.0" }`.
- Add `GET /apps?enabled=true` filtering (already supported; ensure documented).
- Add CORS (`FastAPI CORSMiddleware`) for future web admin tools.

## Test Instructions
- `cd ~/Documents/CouchSuite/CouchLauncherFX`
- `gradle wrapper --gradle-version 9.1 --distribution-type bin || true`
- `./gradlew run`
- Run to test. If errors occur, print and explain each error and propose a fix. If no error, wait for user to finish testing.
