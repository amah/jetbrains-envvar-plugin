package com.example.pluginenvvar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EnvironmentBridge(
    private val project: Project,
    private val browser: JBCefBrowser
) : Disposable {

    private val logger = Logger.getInstance(EnvironmentBridge::class.java)
    private val json = Json { encodeDefaults = true }

    // Query for requesting env vars refresh
    private val envVarsQuery = JBCefJSQuery.create(browser)

    // Query for HTTP requests
    private val httpRequestQuery = JBCefJSQuery.create(browser)

    // Node.js process manager
    private val nodeManager = NodeProcessManager()

    init {
        Disposer.register(browser, this)

        // Handler for env vars refresh
        envVarsQuery.addHandler { _ ->
            if (logger.isDebugEnabled) {
                logger.debug("JS requested environment variable refresh")
            }
            publishEnvVars()
            null
        }

        // Handler for HTTP requests from JS
        httpRequestQuery.addHandler { request ->
            handleHttpRequest(request)
            null
        }
    }

    fun attach() {
        val bridgeScript = """
            (function() {
              const envListeners = [];
              const httpListeners = [];

              function dispatchEnvVars(payload) {
                envListeners.forEach(cb => {
                  try { cb(payload); } catch (err) { console.error('EnvVar listener error', err); }
                });
              }

              function dispatchHttpResult(payload) {
                httpListeners.forEach(cb => {
                  try { cb(payload); } catch (err) { console.error('HTTP listener error', err); }
                });
              }

              window.PluginEnvVarBridge = {
                // Environment variables API
                requestEnvVars: function() {
                  ${envVarsQuery.inject("'refresh'")};
                },
                onEnvVars: function(callback) {
                  if (typeof callback === 'function') {
                    envListeners.push(callback);
                    return () => {
                      const idx = envListeners.indexOf(callback);
                      if (idx >= 0) envListeners.splice(idx, 1);
                    };
                  }
                  return function() {};
                },

                // HTTP Request API
                executeHttpRequest: function(url, method, headers, enableTrace) {
                  const request = JSON.stringify({
                    url: url,
                    method: method || 'GET',
                    headers: headers || {},
                    enableTrace: enableTrace !== false
                  });
                  ${httpRequestQuery.inject("request")};
                },
                onHttpResult: function(callback) {
                  if (typeof callback === 'function') {
                    httpListeners.push(callback);
                    return () => {
                      const idx = httpListeners.indexOf(callback);
                      if (idx >= 0) httpListeners.splice(idx, 1);
                    };
                  }
                  return function() {};
                },

                // Internal dispatch methods
                __dispatchEnvVars: dispatchEnvVars,
                __dispatchHttpResult: dispatchHttpResult
              };

              window.dispatchEvent(new CustomEvent('plugin-env-var-bridge-ready'));
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(bridgeScript, browser.cefBrowser.url, 0)

        // Start Node.js process proactively
        nodeManager.ensureRunning(
            onReady = { logger.info("Node.js agent is ready") },
            onError = { error -> logger.warn("Node.js agent error: $error") }
        )
    }

    fun publishEnvVars() {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        // Get environment variables from Node.js process
        nodeManager.requestEnvVars { entries ->
            val payload = EnvFormatter.asJsonFromEntries(entries)

            app.invokeLater {
                if (logger.isDebugEnabled) {
                    logger.debug("Dispatching ${entries.size} env vars from Node.js to webview")
                }
                browser.cefBrowser.executeJavaScript(
                    "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchEnvVars($payload);",
                    browser.cefBrowser.url,
                    0
                )
            }
        }
    }

    private fun handleHttpRequest(requestJson: String) {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        try {
            val request = json.decodeFromString<HttpRequestParams>(requestJson)

            logger.info("Executing HTTP request via Node.js: ${request.method} ${request.url}")

            nodeManager.executeHttpRequest(
                url = request.url,
                method = request.method,
                headers = request.headers,
                enableTrace = request.enableTrace
            ) { result ->
                // Convert result to JSON and send to webview
                val resultJson = json.encodeToString(result)

                app.invokeLater {
                    browser.cefBrowser.executeJavaScript(
                        "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchHttpResult($resultJson);",
                        browser.cefBrowser.url,
                        0
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to handle HTTP request", e)

            val errorResult = """{"success":false,"error":{"message":"${e.message?.replace("\"", "\\\"")}"}}"""
            app.invokeLater {
                browser.cefBrowser.executeJavaScript(
                    "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchHttpResult($errorResult);",
                    browser.cefBrowser.url,
                    0
                )
            }
        }
    }

    override fun dispose() {
        envVarsQuery.dispose()
        httpRequestQuery.dispose()
        nodeManager.dispose()
    }
}

@kotlinx.serialization.Serializable
data class HttpRequestParams(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val enableTrace: Boolean = true
)
