package com.example.pluginenvvar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery

class EnvironmentBridge(
    private val project: Project,
    private val browser: JBCefBrowser
) : Disposable {

    private val logger = Logger.getInstance(EnvironmentBridge::class.java)
    private val query = JBCefJSQuery.create(browser)

    init {
        Disposer.register(browser, this)
        query.addHandler { _ ->
            if (logger.isDebugEnabled) {
                logger.debug("JS requested environment variable refresh")
            }
            publishEnvVars()
            null
        }
    }

    fun attach() {
        val bridgeScript = """
            (function() {
              const listeners = [];
              function dispatch(payload) {
                listeners.forEach(cb => {
                  try {
                    cb(payload);
                  } catch (err) {
                    console.error('PluginEnvVarBridge listener error', err);
                  }
                });
              }
              window.PluginEnvVarBridge = {
                requestEnvVars: function() {
                  ${query.inject("'refresh'")};
                },
                onEnvVars: function(callback) {
                  if (typeof callback === 'function') {
                    listeners.push(callback);
                    return () => {
                      const idx = listeners.indexOf(callback);
                      if (idx >= 0) {
                        listeners.splice(idx, 1);
                      }
                    };
                  }
                  return function() {};
                },
                __dispatch: dispatch
              };
              window.dispatchEvent(new CustomEvent('plugin-env-var-bridge-ready'));
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(bridgeScript, browser.cefBrowser.url, 0)
    }

    fun publishEnvVars() {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) {
            return
        }
        val envSnapshot = System.getenv().toMap()
        if (logger.isDebugEnabled) {
            logger.debug("Publishing ${envSnapshot.size} environment variables for project '${project.name}'")
        }
        val payload = EnvFormatter.asJson(envSnapshot)
        app.invokeLater {
            logger.info("PluginEnvVarBridge dispatching payload to webview with ${envSnapshot.size} entries")
            browser.cefBrowser.executeJavaScript(
                "window.PluginEnvVarBridge && window.PluginEnvVarBridge.__dispatch(${payload});",
                browser.cefBrowser.url,
                0
            )
        }
    }

    override fun dispose() {
        query.dispose()
    }
}
