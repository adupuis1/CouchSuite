# Repository Guidelines

## Project Structure & Module Organization
`couchserver/` holds the FastAPI backend (`server.py`, `requirements.txt`, `seed.sql`, migrations-in-progress) plus helper scripts. Place new API routers under `couchserver/routers/` if added, and mirror business logic in `services/`. `CouchLauncherFX/` contains the JavaFX client driven by Gradle; UI controllers and views live in `src/main/java/app/`, resources under `src/main/resources/`. Store cross-cutting artifacts in `sharedDirectory/`, and keep repo-root scripts (`start_server.sh`, `toggle_server.sh`) aligned with backend endpoints.

## Build, Test, and Development Commands
Run `cd couchserver && python -m venv .venv && .venv/bin/pip install -r requirements.txt` to prepare the backend environment. Launch the API with `.venv/bin/uvicorn server:app --reload`. For the client, execute `cd CouchLauncherFX && ./gradlew clean build` before every push, and use `./gradlew run` for manual smoke tests. The Steam charts job will eventually live under a scheduler; document any new CLI entry points in `couchserver/README.md`.

## Coding Style & Naming Conventions
Follow PEP 8 for Python, snake_case for modules/functions, and keep Pydantic models in `CamelCase`. Prefer dependency-injected services over module-level globals. In Java, retain 4-space indentation, UpperCamelCase classes (`Main`, `HttpRepo`), and lowerCamelCase fields/methods. Name FXML or layout assets after their controller (e.g., `HubView.fxml`). Keep JSON config in ASCII and deterministic key order.

## Testing Guidelines
Server tests belong in `couchserver/tests/` with `pytest` and `httpx` for API calls; load fixtures via `seed.sql`. Name files `test_<feature>.py`. Client tests should live in `CouchLauncherFX/src/test/java/app/`, use JUnit 5, and isolate network calls through `HttpRepo`. Target coverage for tenancy, `/apps` fetch, and session launch flows. Run `pytest` and `./gradlew test` locally before requesting review.

## Commit & Pull Request Guidelines
Structure commits around a single concern and use concise subjects (`server: add version endpoint`). History shows semantic tags (`0.0.x`, `CI: ...`); follow that pattern. Pull requests must outline tenant impact, linked issues, validation commands, and any UI/API screenshots or JSON diffs. Confirm both server and client builds succeed locally—CI should only re-validate.

## Security & Configuration Tips
Never commit secrets; configure `COUCHSUITE_USERNAME_SECRET`, database URLs, and Redis endpoints via environment variables. Audit new routes for tenant isolation and RBAC, and add rate limiting as endpoints scale. Keep `~/.config/couchlauncherfx/` caches scrubbed of user data when attaching logs.
