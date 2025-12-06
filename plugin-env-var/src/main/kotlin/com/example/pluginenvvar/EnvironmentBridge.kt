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

    // Query for node path configuration
    private val nodeConfigQuery = JBCefJSQuery.create(browser)

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

        // Handler for node configuration
        nodeConfigQuery.addHandler { request ->
            handleNodeConfig(request)
            null
        }
    }

    fun attach() {
        val bridgeScript = """
            (function() {
              const envListeners = [];
              const httpListeners = [];
              const nodeInfoListeners = [];

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

              function dispatchNodeInfo(payload) {
                nodeInfoListeners.forEach(cb => {
                  try { cb(payload); } catch (err) { console.error('NodeInfo listener error', err); }
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

                // Node.js configuration API
                setNodePath: function(nodePath) {
                  const request = JSON.stringify({ action: 'setPath', nodePath: nodePath || '' });
                  ${nodeConfigQuery.inject("request")};
                },
                getNodeInfo: function() {
                  const request = JSON.stringify({ action: 'getInfo' });
                  ${nodeConfigQuery.inject("request")};
                },
                onNodeInfo: function(callback) {
                  if (typeof callback === 'function') {
                    nodeInfoListeners.push(callback);
                    return () => {
                      const idx = nodeInfoListeners.indexOf(callback);
                      if (idx >= 0) nodeInfoListeners.splice(idx, 1);
                    };
                  }
                  return function() {};
                },

                // Internal dispatch methods
                __dispatchEnvVars: dispatchEnvVars,
                __dispatchHttpResult: dispatchHttpResult,
                __dispatchNodeInfo: dispatchNodeInfo
              };

              window.dispatchEvent(new CustomEvent('plugin-env-var-bridge-ready'));
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(bridgeScript, browser.cefBrowser.url, 0)

        // Start Node.js process proactively
        nodeManager.ensureRunning(
            onReady = {
                logger.info("Node.js agent is ready")
                publishNodeInfo()
            },
            onError = { error ->
                logger.warn("Node.js agent error: $error")
                publishNodeInfo()
            }
        )
    }

    fun publishEnvVars() {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        // Get JVM environment variables
        val jvmEnvVars = System.getenv().toMap()
        val jvmPayload = EnvFormatter.asJson(jvmEnvVars)

        // Get environment variables from Node.js process
        nodeManager.requestEnvVars { entries ->
            val nodePayload = EnvFormatter.asJsonFromEntries(entries)

            app.invokeLater {
                if (logger.isDebugEnabled) {
                    logger.debug("Dispatching ${entries.size} Node.js env vars and ${jvmEnvVars.size} JVM env vars to webview")
                }
                // Send combined payload with both JVM and Node.js env vars
                val combinedPayload = """{"jvm":$jvmPayload,"node":$nodePayload}"""
                browser.cefBrowser.executeJavaScript(
                    "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchEnvVars($combinedPayload);",
                    browser.cefBrowser.url,
                    0
                )
            }
        }
    }

    private fun publishNodeInfo() {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        val nodeInfo = nodeManager.getNodeInfo()
        val nodeInfoJson = json.encodeToString(nodeInfo)

        app.invokeLater {
            browser.cefBrowser.executeJavaScript(
                "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchNodeInfo($nodeInfoJson);",
                browser.cefBrowser.url,
                0
            )
        }
    }

    private fun handleNodeConfig(requestJson: String) {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return

        try {
            val request = json.decodeFromString<NodeConfigRequest>(requestJson)

            when (request.action) {
                "setPath" -> {
                    logger.info("Setting Node.js path to: ${request.nodePath}")
                    nodeManager.restartWithPath(request.nodePath) { nodeInfo ->
                        val nodeInfoJson = json.encodeToString(nodeInfo)
                        app.invokeLater {
                            browser.cefBrowser.executeJavaScript(
                                "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatchNodeInfo($nodeInfoJson);",
                                browser.cefBrowser.url,
                                0
                            )
                            // Also refresh env vars after node restart
                            publishEnvVars()
                        }
                    }
                }
                "getInfo" -> {
                    publishNodeInfo()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to handle node config request", e)
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
        nodeConfigQuery.dispose()
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

@kotlinx.serialization.Serializable
data class NodeConfigRequest(
    val action: String,
    val nodePath: String? = null
)
