# JetBrains Environment Variable Plugin

This repository contains a sample IntelliJ Platform plugin that surfaces environment variables inside the IDE via a dedicated tool window. The UI is rendered through a bundled webview (Vite + React) and communicates with Kotlin host code through a lightweight bridge.

## Projects

- `plugin-env-var/` – Kotlin sources, plugin metadata, and Gradle build logic.
- `webview-ui/` – Vite + React project compiled into the tool window web bundle.

## Getting Started

1. Install dependencies:
   - Ensure Java 17 (or newer) and Node.js 18+ are available.
   - Run `npm ci` inside `webview-ui/`.
2. Build the web assets: `npm run build` inside `webview-ui/` (outputs to `dist/`).
3. Build and launch the plugin:
   ```bash
   ./gradlew buildPlugin
   ./gradlew runIde
   ```

While the Kotlin host code ships with a static fallback page, the tool window expects the Vite bundle located in `webview-ui/dist`. Re-run the Gradle tasks after rebuilding the webview to refresh the packaged assets.

## Creating Releases

This project uses GitHub Actions to automatically build and release the plugin.

### Automatic Build
- Every push to `main` branch triggers a build
- Pull requests are also built automatically
- Build artifacts are uploaded and available for download

### Creating a Release

To create a new release with the plugin package:

1. **Create and push a version tag:**
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

2. **GitHub Actions will automatically:**
   - Build the webview (Vite + React)
   - Build the plugin (Kotlin + bundled webview)
   - Create a GitHub Release
   - Attach the plugin `.zip` file to the release

3. **Download the plugin:**
   - Go to the [Releases page](../../releases)
   - Download the `.zip` file from the latest release
   - Install in IntelliJ via: Settings → Plugins → ⚙️ → Install Plugin from Disk

### Version Numbering
Use semantic versioning for tags: `v<major>.<minor>.<patch>`
- Example: `v0.1.0`, `v1.0.0`, `v1.2.3`
