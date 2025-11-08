# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a JetBrains IntelliJ Platform plugin that displays system environment variables in a tool window. The UI is rendered using a React + Vite webview that communicates with the Kotlin host through a JavaScript bridge.

**Repository structure:**
- `plugin-env-var/` - Kotlin plugin code (IntelliJ Platform SDK)
- `webview-ui/` - React/TypeScript UI (bundled into plugin resources)

## Build Commands

### Full plugin build (includes webview)
```bash
./gradlew buildPlugin
```

This automatically runs `webviewBuild` which chains `webviewNpmInstall` → `npm run build`.

### Launch plugin in sandbox IDE
```bash
./gradlew runIde
```

### Run tests
```bash
# Kotlin tests
./gradlew :plugin-env-var:check

# Frontend tests
cd webview-ui && npm test

# Lint frontend
cd webview-ui && npm run lint
```

### Webview development
```bash
cd webview-ui
npm ci              # Install dependencies
npm run build       # Production build → dist/
npm run dev         # Development server (not used in plugin)
```

## Architecture

### Kotlin → JavaScript Bridge

The plugin uses **JCEF (Java Chromium Embedded Framework)** via `JBCefBrowser` to render the webview.

**Bridge initialization flow:**
1. `PluginEnvVarToolWindowFactory` creates a `JBCefBrowser` instance
2. `EnvironmentBridge` is instantiated with the browser reference
3. On main frame load (`CefLoadHandlerAdapter.onLoadEnd`):
   - `bridge.attach()` injects `window.PluginEnvVarBridge` JavaScript object
   - `bridge.publishEnvVars()` sends initial environment data to webview

**Bridge API (injected into webview global scope):**
```javascript
window.PluginEnvVarBridge = {
  requestEnvVars: () => void,           // Request fresh env data from Kotlin
  onEnvVars: (callback) => unsubscribe, // Subscribe to env updates
  __dispatch: (payload) => void         // Internal: push data to listeners
}
```

**Data flow:**
- **Kotlin → JS:** `EnvironmentBridge.publishEnvVars()` calls `executeJavaScript()` to invoke `__dispatch()`
- **JS → Kotlin:** `requestEnvVars()` triggers `JBCefJSQuery` handler which calls `publishEnvVars()`

### Environment Variable Masking

`EnvFormatter` (plugin-env-var/src/main/kotlin/com/example/pluginenvvar/EnvFormatter.kt:11) automatically masks values for keys containing: `KEY`, `SECRET`, `TOKEN`, `PASSWORD`, `PWD`, `CREDENTIAL` (case-insensitive).

**Payload format:**
```kotlin
data class EnvVariablePayload(
    val key: String,
    val value: String,    // "••••" if sensitive
    val sensitive: Boolean
)
```

Variables are sorted alphabetically by key for stable frontend diffing.

### Webview Resource Loading

**Production:** Bundled webview files are copied from `webview-ui/dist/` to plugin resources at `/webview/` during `processResources` task.

**IMPORTANT - JCEF Module Loading:** JCEF cannot load ES modules (`<script type="module">`) directly from jar: URLs due to CORS restrictions. The plugin uses `loadHTML(content, baseUrl)` instead of `loadURL()`:

1. Reads HTML content as string via `getResourceAsStream("/webview/index.html")`
2. Gets base URL from `getResource("/webview/index.html").toExternalForm()`
3. Constructs directory base URL by replacing filename with trailing slash
4. Calls `browser.loadHTML(htmlContent, baseUrl)` to allow proper resource resolution

This approach allows JCEF to serve the Vite-built JavaScript modules without security restrictions.

**Fallback:** If `/webview/index.html` is not found in resources, `PluginEnvVarToolWindowFactory.fallbackHtml()` displays an error message prompting to run `npm run build`.

## Development Workflow

1. **Make webview changes:**
   ```bash
   cd webview-ui
   npm run build
   ```

2. **Rebuild plugin to package new assets:**
   ```bash
   ./gradlew buildPlugin
   ```

3. **Test in sandbox IDE:**
   ```bash
   ./gradlew runIde
   ```
   The tool window appears on the right sidebar as "Environment Variables".

4. **Debug mode:** When launched via `runIde`, system property `PLUGIN_ENV_VAR_DEBUG=true` is set. Check `EnvironmentBridge` logging for bridge activity.

## Key Files

- `plugin-env-var/src/main/kotlin/com/example/pluginenvvar/`
  - `PluginEnvVarToolWindowFactory.kt` - Tool window creation, JCEF setup, fallback HTML
  - `EnvironmentBridge.kt` - Bridge injection, JS ↔ Kotlin communication
  - `EnvFormatter.kt` - Environment variable serialization and masking

- `plugin-env-var/src/main/resources/META-INF/plugin.xml` - Plugin metadata and tool window registration

- `webview-ui/src/`
  - `bridge.d.ts` - TypeScript definitions for `window.PluginEnvVarBridge`
  - `App.tsx` - Main React component with filtering, refresh, and copy functionality
  - `main.tsx` - Entry point, waits for `plugin-env-var-bridge-ready` event

## Requirements

- **Java:** 17+ (configured in `build.gradle.kts`)
- **Node.js:** 18+ (for webview build)
- **IntelliJ Platform:** 2023.2+ (plugin target version)
