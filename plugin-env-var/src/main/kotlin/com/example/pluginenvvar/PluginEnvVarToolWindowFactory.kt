package com.example.pluginenvvar

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class PluginEnvVarToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = JBCefBrowser()
        val content = ContentFactory.getInstance().createContent(browser.component, null, false)
        toolWindow.contentManager.addContent(content)

        val bridge = EnvironmentBridge(project, browser)
        val loadHandler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    bridge.attach()
                    bridge.publishEnvVars()
                }
            }
        }

        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)

        Disposer.register(toolWindow.disposable) {
            browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        }
        Disposer.register(toolWindow.disposable, bridge)

        if (!loadBundledWebview(browser)) {
            browser.loadHTML(fallbackHtml(), FALLBACK_BASE_URL)
        }
    }

    private fun loadBundledWebview(browser: JBCefBrowser): Boolean {
        // Read JavaScript and CSS from resources and inline them
        val js = javaClass.getResourceAsStream("/webview/assets/index.js")?.use { stream ->
            stream.bufferedReader().readText()
        } ?: return false

        val css = javaClass.getResourceAsStream("/webview/assets/index.css")?.use { stream ->
            stream.bufferedReader().readText()
        } ?: return false

        // Create inline HTML with embedded JS and CSS
        val inlineHtml = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Environment Variable Viewer</title>
                <style>
                $css

                /* Responsive table styling */
                html, body {
                  margin: 0;
                  padding: 0;
                  width: 100%;
                  height: 100%;
                  overflow: auto;
                }

                #root {
                  min-width: 300px;
                  width: 100%;
                  height: 100%;
                }

                table {
                  width: 100%;
                  min-width: 300px;
                  table-layout: auto;
                }

                th, td {
                  word-wrap: break-word;
                  overflow-wrap: break-word;
                }
                </style>
              </head>
              <body>
                <div id="root"></div>
                <script>
                $js
                </script>
              </body>
            </html>
        """.trimIndent()

        browser.loadHTML(inlineHtml, "http://plugin-env-var.local/")
        return true
    }

    private fun fallbackHtml(): String = """
        <html lang=\"en\">
          <head>
            <meta charset=\"UTF-8\" />
            <title>Environment Variables</title>
            <style>
              body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; padding: 16px; }
              .warning { color: #c0392b; }
            </style>
          </head>
          <body>
            <h2>Environment Variables</h2>
            <p class=\"warning\">webview build missing. Run <code>npm run build</code> inside <code>webview-ui/</code>.</p>
          </body>
        </html>
    """.trimIndent()

    companion object {
        private const val FALLBACK_BASE_URL = "https://plugin-env-var.local/index.html"
    }
}
