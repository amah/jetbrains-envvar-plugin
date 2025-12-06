package com.example.pluginenvvar

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages a Node.js child process for environment variable collection and HTTP requests.
 *
 * The Node.js process is spawned on-demand and communicates via JSON over stdin/stdout.
 */
class NodeProcessManager : Disposable {

    private val logger = Logger.getInstance(NodeProcessManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val requestIdCounter = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks for different message types
    private var onEnvVarsResult: ((List<EnvVarEntry>) -> Unit)? = null
    private var onHttpResult: ((HttpResult) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // Pending HTTP requests waiting for response
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<HttpResult>>()

    @Serializable
    data class EnvVarEntry(val key: String, val value: String)

    @Serializable
    data class HttpResult(
        val id: String = "",
        val success: Boolean = false,
        val statusCode: Int? = null,
        val statusMessage: String? = null,
        val headers: Map<String, JsonElement>? = null,
        val body: String? = null,
        val error: HttpError? = null,
        val trace: List<TraceEvent> = emptyList(),
        val timing: Timing? = null
    )

    @Serializable
    data class HttpError(val message: String, val code: String? = null)

    @Serializable
    data class TraceEvent(
        val timestamp: Long,
        val phase: String,
        val event: String
    )

    @Serializable
    data class Timing(val total: Long)

    /**
     * Start the Node.js process if not already running.
     */
    fun ensureRunning(
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRunning.get()) {
            onReady()
            return
        }

        this.onReady = onReady
        this.onError = onError

        scope.launch {
            try {
                startProcess()
            } catch (e: Exception) {
                logger.error("Failed to start Node.js process", e)
                onError("Failed to start Node.js: ${e.message}")
            }
        }
    }

    private suspend fun startProcess() {
        val nodePath = getNodePath()
        val agentScript = extractAgentScript()

        logger.info("Starting Node.js process: $nodePath ${agentScript.absolutePath}")

        val processBuilder = ProcessBuilder(nodePath, agentScript.absolutePath)
            .redirectErrorStream(false)

        // Inherit environment but allow customization
        processBuilder.environment().putAll(System.getenv())

        process = processBuilder.start()
        writer = process!!.outputStream.bufferedWriter()

        isRunning.set(true)

        // Start reading stdout in a coroutine
        readerJob = scope.launch {
            readProcessOutput()
        }

        // Also log stderr
        scope.launch {
            readProcessErrors()
        }
    }

    private fun getNodePath(): String {
        // Check for custom node path environment variable
        val customPath = System.getenv("JB_ENVVAR_NODE_PATH")
        if (!customPath.isNullOrBlank()) {
            logger.info("Using custom Node.js path from JB_ENVVAR_NODE_PATH: $customPath")
            return customPath
        }

        // Use system node
        return "node"
    }

    private fun extractAgentScript(): File {
        // Extract the agent.js from resources to a temp file
        val resourcePath = "/node-agent/agent.js"
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Node agent script not found in resources: $resourcePath")

        val tempDir = Files.createTempDirectory("jb-envvar-plugin").toFile()
        tempDir.deleteOnExit()

        val agentFile = File(tempDir, "agent.js")
        agentFile.deleteOnExit()

        inputStream.use { input ->
            agentFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        logger.info("Extracted Node agent to: ${agentFile.absolutePath}")
        return agentFile
    }

    private suspend fun readProcessOutput() {
        val reader = process?.inputStream?.bufferedReader() ?: return

        try {
            while (isRunning.get()) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break

                if (line.isBlank()) continue

                try {
                    handleMessage(line)
                } catch (e: Exception) {
                    logger.warn("Failed to parse Node.js message: $line", e)
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                logger.warn("Error reading from Node.js process", e)
            }
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun readProcessErrors() {
        val reader = process?.errorStream?.bufferedReader() ?: return

        try {
            while (isRunning.get()) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break

                logger.info("[node-agent stderr] $line")
            }
        } catch (e: IOException) {
            // Ignore
        }
    }

    private fun handleMessage(line: String) {
        val jsonElement = json.parseToJsonElement(line)
        val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.content

        when (type) {
            "ready" -> {
                val nodeVersion = jsonElement.jsonObject["nodeVersion"]?.jsonPrimitive?.content
                val pid = jsonElement.jsonObject["pid"]?.jsonPrimitive?.intOrNull
                logger.info("Node.js agent ready (version: $nodeVersion, pid: $pid)")
                onReady?.invoke()
            }

            "envVarsResult" -> {
                val data = jsonElement.jsonObject["data"]
                if (data != null) {
                    val entries = json.decodeFromJsonElement<List<EnvVarEntry>>(data)
                    onEnvVarsResult?.invoke(entries)
                }
            }

            "httpResult" -> {
                val result = json.decodeFromJsonElement<HttpResult>(jsonElement)
                val requestId = result.id

                // Complete pending request if exists
                pendingRequests.remove(requestId)?.complete(result)

                // Also invoke callback
                onHttpResult?.invoke(result)
            }

            "pong" -> {
                logger.debug("Received pong from Node.js agent")
            }

            "error" -> {
                val message = jsonElement.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error"
                logger.warn("Node.js agent error: $message")
                onError?.invoke(message)
            }

            "shutdownAck" -> {
                logger.info("Node.js agent acknowledged shutdown")
            }

            else -> {
                logger.warn("Unknown message type from Node.js: $type")
            }
        }
    }

    private fun sendCommand(command: JsonObject) {
        val writer = this.writer ?: return
        val line = command.toString()

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    synchronized(writer) {
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                    }
                }
            } catch (e: IOException) {
                logger.warn("Failed to send command to Node.js", e)
            }
        }
    }

    /**
     * Request environment variables from the Node.js process.
     */
    fun requestEnvVars(callback: (List<EnvVarEntry>) -> Unit) {
        onEnvVarsResult = callback

        if (!isRunning.get()) {
            ensureRunning(
                onReady = {
                    sendCommand(buildJsonObject { put("type", "getEnvVars") })
                },
                onError = { error ->
                    logger.warn("Cannot get env vars: $error")
                    callback(emptyList())
                }
            )
        } else {
            sendCommand(buildJsonObject { put("type", "getEnvVars") })
        }
    }

    /**
     * Execute an HTTP request via the Node.js process.
     */
    fun executeHttpRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        enableTrace: Boolean = true,
        callback: (HttpResult) -> Unit
    ) {
        val requestId = "req-${requestIdCounter.incrementAndGet()}"
        onHttpResult = callback

        val command = buildJsonObject {
            put("type", "httpRequest")
            put("id", requestId)
            put("url", url)
            put("method", method)
            put("enableTrace", enableTrace)
            put("headers", buildJsonObject {
                headers.forEach { (k, v) -> put(k, v) }
            })
        }

        if (!isRunning.get()) {
            ensureRunning(
                onReady = { sendCommand(command) },
                onError = { error ->
                    callback(HttpResult(
                        id = requestId,
                        success = false,
                        error = HttpError(error)
                    ))
                }
            )
        } else {
            sendCommand(command)
        }
    }

    /**
     * Check if the Node.js process is running.
     */
    fun isProcessRunning(): Boolean = isRunning.get() && process?.isAlive == true

    /**
     * Shutdown the Node.js process.
     */
    fun shutdown() {
        if (!isRunning.get()) return

        logger.info("Shutting down Node.js agent")
        isRunning.set(false)

        try {
            sendCommand(buildJsonObject { put("type", "shutdown") })

            // Give it a moment to shut down gracefully
            process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Error during graceful shutdown", e)
        }

        try {
            writer?.close()
            process?.destroyForcibly()
        } catch (e: Exception) {
            logger.warn("Error destroying Node.js process", e)
        }

        readerJob?.cancel()
        process = null
        writer = null
    }

    override fun dispose() {
        shutdown()
        scope.cancel()
    }
}
