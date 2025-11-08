# Repository Guidelines

## Project Structure & Module Organization
Source control is split into Gradle and platform-specific modules. `plugin-gradle/` holds the root Kotlin DSL build files and shared configurations. The IntelliJ plugin code lives in `plugin-env-var/`, with Kotlin sources under `src/main/kotlin/com/example/pluginenvvar` and bundled resources in `src/main/resources` (web assets end up in `resources/webview`). The `webview-ui/` directory contains the Vite + React dashboard that renders environment variables. Tests mirror their sources (`plugin-env-var/src/test/kotlin`, `webview-ui/src/__tests__`).

## Build, Test, and Development Commands
`./gradlew runIde` launches a sandboxed IDE with the plugin installed; set `PLUGIN_ENV_VAR_DEBUG=true` to enable verbose logging. Use `./gradlew buildPlugin` to produce a distributable ZIP, and `./gradlew check` for aggregate Kotlin compilation, linting, and unit tests. Inside `webview-ui/`, run `npm ci` once, then `npm run dev` for local UI tweaks, `npm run build` to emit production assets, and `npm run lint` to enforce TypeScript style.

## Coding Style & Naming Conventions
Follow JetBrains Kotlin style (4-space indents, PascalCase types, camelCase members). Prefer explicit visibility and mark nullable API surfaces with clear docs. Kotlin files should include brief KDoc on public entry points. The web module uses ESLint + Prettier; keep React components in PascalCase files and colocate hooks under `use*.ts`. Commit generated assets only from CI output directories.

## Testing Guidelines
Kotlin tests use JUnit 5; place them under matching package paths and name files `*Test.kt`. Run `./gradlew test` (or the broader `check`) before pushing. The frontend uses Vitest; add specs in `src/__tests__/` with filenames ending in `.spec.tsx` and execute via `npm run test -- --watch`. Aim for meaningful coverage on the EnvFormatter, bridge contracts, and table rendering logic; add fixtures for sensitive-value masking scenarios.

## Commit & Pull Request Guidelines
Adopt Conventional Commits (`feat:`, `fix:`, `chore:`) with optional scopes (`feat(plugin-env-var): …`). Reference issue IDs where possible. Each PR should describe motivation, implementation notes, and verification (commands run, screenshots of the tool window if UI changed). Keep PRs focused by module, ensure CI (`./gradlew check` and `npm run lint`) passes locally, and request reviews once lint and tests are green.

## Security & Configuration Tips
Never commit real environment dumps; rely on mock data in tests. Document any new env keys in the tool window help section. When debugging, prefer temporary overrides through `PLUGIN_ENV_VAR_DEBUG` rather than printing secrets, and scrub logs before sharing traces.
