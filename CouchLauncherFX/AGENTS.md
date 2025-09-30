# Repository Guidelines

## Project Structure & Module Organization
- `build.gradle` and `settings.gradle` declare the Java 25 + JavaFX toolchain and point to the `app.Main` entrypoint.
- Core source lives in `src/main/java/app/`; keep UI controllers, data access, and launcher utilities within this package to simplify module wiring.
- Static assets (CSS, images, FXML when added) belong in `src/main/resources/`; `application.css` is the base theme.
- Use the same package layout under `src/test/java/` when you introduce unit or integration tests to mirror production code.

## Build, Test, and Development Commands
- `./gradlew clean build` — compile sources and package the application; run before opening a PR.
- `./gradlew run` — launch the JavaFX app with the stub dialogs (macOS-safe default).
- `./gradlew test` — execute the JUnit test suite; add it to your local pre-push checklist.
- `./gradlew -q dependencies` — quick check that dependency trees stay lean after adding libraries.

## Coding Style & Naming Conventions
- Follow standard Java conventions: 4-space indentation, K&R braces, one top-level class per file.
- Classes and enums use PascalCase (`AppTile`), methods and fields use camelCase, constants use UPPER_SNAKE_CASE.
- Keep UI strings and CSS selectors consistent with existing tiles; prefer extracting shared styles to `application.css`.
- Platform-dependent logic must be guarded behind OS checks or feature flags (see `AGENT.md`).

## Testing Guidelines
- Target JUnit 5 (Gradle’s default); place specs in `src/test/java/app/` mirroring the production package.
- Name test classes `*Test` or `*IT` for integration-level coverage; favor descriptive method names (`launchesSteamTileWhenEnabled`).
- Ensure every new branch (e.g., Moonlight process calls, DB fetches) gets a positive and failing-path test.
- Run `./gradlew test` before committing; add `./gradlew run` for UI-facing changes to confirm manual flows.

## Commit & Pull Request Guidelines
- Commits should start with an imperative subject ≤72 chars and include a short `Commit message:` explanation block as noted in `AGENT.md`.
- Keep patches focused and idempotent; document config or schema changes in the body.
- Pull requests need a brief summary, linked issue/task, and screenshots or screen recordings for UI tweaks.
- Re-run `./gradlew clean build` and attach relevant logs when reporting flaky failures.

## Agent-Specific Notes
- Never hardcode secrets; rely on `CL_DB_URL`, `CL_DB_USER`, and `CL_DB_PASS` for database access.
- Default to macOS-safe behaviors; wrap Linux-only Moonlight launches with environment checks.
- Prefer additive migrations and scripts that can run multiple times without side effects.
