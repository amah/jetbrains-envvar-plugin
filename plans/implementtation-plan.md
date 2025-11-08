 Repo Blueprint

  - plugin-gradle/ root with Gradle Kotlin DSL, configured via org.jetbrains.intellij plugin; target IntelliJ 2023.2+.
  - plugin-env-var/ module (JVM/Kotlin) housing plugin sources, resources, and Gradle build logic.
  - webview-ui/ module for Vite/React (or vanilla TS) app that renders environment variables.

  Kotlin Host Implementation

  - src/main/kotlin/com/example/pluginenvvar/PluginEnvVarToolWindowFactory.kt registers a tool window; loads bundled web assets into JBCefBrowser.
  - EnvironmentBridge.kt sets up JBCefJSQuery to respond to JS requests and push System.getenv() results.
  - Optional EnvFormatter utility to sort/filter and mask sensitive values before sending to UI.
  - Logging via Logger.getInstance(...) with optional verbose mode gated by PLUGIN_ENV_VAR_DEBUG env var.

  Webview Runtime

  - Vite/React app with entry src/main.tsx that:
      - Calls window.PluginEnvVarBridge.requestEnvVars() on mount.
      - Listens for message events (type: 'env-vars') to populate state.
      - Renders searchable/filterable table (key, value, copy button) with refresh button.
  - Define bridge typings in src/bridge.d.ts mirroring host contract.

  Bridge Contract

  - Global injected object window.PluginEnvVarBridge exposing requestEnvVars() and onEnvVars(callback).
  - Messages serialized as { type: 'env-vars'; payload: Array<{ key: string; value: string }> }.
  - Support incremental updates (future) via { type: 'env-vars:update'; payload: { key, value } }.

  Build & Packaging Flow

  - Gradle task :webview-ui:build (npm ci && npm run build) wired as dependency of :plugin-env-var:buildPlugin; output copied to plugin-env-var/src/main/
  resources/webview.
  - Configure plugin.xml with tool window registration, action ID, vendor info, change notes.
  - Provide runIde configuration for local testing; document how to run with PLUGIN_ENV_VAR_DEBUG=true.
  - Add GitHub Actions workflow running ./gradlew check buildPlugin and verifying JS build (npm run lint).

  Docs & Kickoff

  - README outlining architecture, build steps, env var bridge contract, debug instructions.
  - CONTRIBUTING stub with coding conventions (Kotlin style, TypeScript lint rules).
  - Follow-up tasks: unit tests for EnvFormatter, Jest/Vitest coverage for React components, optional integration test using IntelliJ UI test framework.

