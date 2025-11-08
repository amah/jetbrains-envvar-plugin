package com.example.pluginenvvar

import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

class WebviewResourceHandler(private val clazz: Class<*>) : CefResourceHandler {
    private var inputStream: InputStream? = null
    private var mimeType: String = "text/html"

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val url = request.url
        println("[WebviewResourceHandler] Request for: $url")

        // Extract the path from the URL
        val resourcePath = when {
            url.contains("/webview/") -> {
                // Extract everything after /webview/
                val path = url.substringAfter("/webview/")
                "/webview/$path"
            }
            url.endsWith("/") || url.endsWith("/index.html") -> "/webview/index.html"
            else -> {
                println("[WebviewResourceHandler] Could not map URL to resource: $url")
                return false
            }
        }

        println("[WebviewResourceHandler] Mapped to resource: $resourcePath")

        // Determine MIME type
        mimeType = when {
            resourcePath.endsWith(".html") -> "text/html"
            resourcePath.endsWith(".js") -> "application/javascript"
            resourcePath.endsWith(".css") -> "text/css"
            resourcePath.endsWith(".json") -> "application/json"
            resourcePath.endsWith(".png") -> "image/png"
            resourcePath.endsWith(".jpg") || resourcePath.endsWith(".jpeg") -> "image/jpeg"
            resourcePath.endsWith(".svg") -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        // Load resource
        val stream = clazz.getResourceAsStream(resourcePath)
        if (stream == null) {
            println("[WebviewResourceHandler] Resource not found: $resourcePath")
            return false
        }

        inputStream = ByteArrayInputStream(stream.readBytes())
        println("[WebviewResourceHandler] Loaded resource: $resourcePath (${mimeType})")
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(
        response: CefResponse,
        responseLength: IntRef,
        redirectUrl: StringRef
    ) {
        response.mimeType = mimeType
        response.status = 200
        response.setHeaderByName("Access-Control-Allow-Origin", "*", false)

        val stream = inputStream
        if (stream != null) {
            responseLength.set(stream.available())
        }
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback
    ): Boolean {
        val stream = inputStream ?: return false

        val read = stream.read(dataOut, 0, bytesToRead)
        if (read > 0) {
            bytesRead.set(read)
            return true
        }

        stream.close()
        return false
    }

    override fun cancel() {
        inputStream?.close()
    }
}
