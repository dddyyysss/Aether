package com.zhousl.aether.data

import android.content.Context
import android.util.Base64
import com.zhousl.aether.data.pi.BrowserDomStableScript
import com.zhousl.aether.data.pi.BrowserPageInfoScript
import com.zhousl.aether.data.pi.BrowserReadableScript
import com.zhousl.aether.data.pi.browserBackboneScript
import com.zhousl.aether.data.pi.browserClickScript
import com.zhousl.aether.data.pi.browserFindElementsScript
import com.zhousl.aether.data.pi.browserGetTextScript
import com.zhousl.aether.data.pi.browserScrollScript
import com.zhousl.aether.data.pi.browserTypeScript
import com.zhousl.aether.data.pi.browserWaitForDomStableScript
import com.zhousl.aether.runtime.AlpineRuntime
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

private const val ChromeCdpPort = 9222
private const val ChromeVncPort = 5900
private const val ChromeNoVncPort = 6080
private const val ChromeViewportWidth = 1080
private const val ChromeViewportHeight = 2040
private const val ChromeBrowserUiKeyboardMinY = 34
private const val ChromeBrowserUiKeyboardMaxY = 100
private const val ChromeStartupTimeoutMillis = 25_000L
private const val ChromeCommandTimeoutMillis = 12_000L
private const val ChromeDataCommandTimeoutMillis = 60_000L
private const val BrowserArtifactRoot = "/root/.aether/browser-cdp"
// Inline data enters the model context as a whole, so keep the defaults small.
private const val BrowserInlineBase64Limit = 8_192
private const val BrowserDefaultResourceLimit = 100
private const val NetworkMaxTotalBufferBytes = 64L * 1024L * 1024L
private const val NetworkMaxResourceBufferBytes = 16L * 1024L * 1024L
private const val NetworkMaxPostDataBytes = 64L * 1024L
private const val ChromeScreenshotQuality = 82
private val HexDigits = "0123456789abcdef".toCharArray()
internal const val AlpineChromeViewerUrl =
    "http://127.0.0.1:$ChromeNoVncPort/vnc_lite.html" +
        "?autoconnect=true&scale=true&show_dot=true&path=websockify"

class AlpineChromeController(
    context: Context,
    private val alpineRuntime: AlpineRuntime,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val cacheDirectory = File(appContext.cacheDir, "alpine-chrome").apply { mkdirs() }
    private val captureDirectory = File(appContext.filesDir, "alpine-chrome/captures").apply { mkdirs() }
    private val networkRegistry = BrowserNetworkRegistry()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val _displayState = MutableStateFlow(
        AgentModeDisplayState(
            width = ChromeViewportWidth,
            height = ChromeViewportHeight,
            status = "Chrome is stopped.",
        )
    )

    @Volatile
    private var chromeProcess: Process? = null
    @Volatile
    private var cdpSession: CdpSession? = null

    val displayState: StateFlow<AgentModeDisplayState> = _displayState.asStateFlow()

    suspend fun execute(argumentsJson: String): String = operationMutex.withLock {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withLock errorResult("Arguments were not valid JSON.")
        val action = arguments.optString("action").trim().lowercase()
        runCatching {
            when (action) {
                "start" -> {
                    ensureStarted()
                    captureResult("Chrome started.")
                }
                "status" -> {
                    if (isRunning()) captureResult("Chrome is running.") else statusResult()
                }
                "navigate", "open" -> {
                    val url = normalizeUrl(arguments.optString("url"))
                    if (url.isBlank()) {
                        errorResult("Missing required 'url' argument.")
                    } else {
                        ensureStarted().send("Page.navigate", JSONObject().put("url", url))
                        delay(650)
                        captureResult("Opened $url")
                    }
                }
                "tap", "click" -> {
                    val selector = arguments.optString("selector").trim()
                    if (selector.isNotBlank()) {
                        val result = evaluateBrowserJson(
                            ensureStarted(),
                            browserClickScript(selector, null, null),
                        )
                        updateCursorFromBrowserResult(result)
                        delay(280)
                        captureResult("Clicked browser element.", result)
                    } else {
                        val x = normalizedCoordinate(arguments, "x", ChromeViewportWidth)
                        val y = normalizedCoordinate(arguments, "y", ChromeViewportHeight)
                        if (x == null || y == null) {
                            errorResult("Provide a CSS selector or both x and y using normalized 0..1000 coordinates.")
                        } else {
                            dispatchTap(ensureStarted(), x, y)
                            updateCursor(x, y, 180)
                            delay(280)
                            captureResult("Clicked browser coordinates.")
                        }
                    }
                }
                "swipe", "scroll" -> {
                    val selector = arguments.optString("selector").trim()
                    if (selector.isNotBlank() || arguments.has("direction") || arguments.has("amount")) {
                        val result = evaluateBrowserJson(
                            ensureStarted(),
                            browserScrollScript(
                                selector = selector,
                                direction = arguments.optString("direction", "down"),
                                amount = arguments.optInt("amount", 600).coerceIn(1, 20_000),
                            ),
                        )
                        updateCursorFromBrowserResult(result)
                        delay(250)
                        captureResult("Scrolled browser.", result)
                    } else {
                        val x1 = normalizedCoordinate(arguments, "x1", ChromeViewportWidth)
                            ?: ChromeViewportWidth / 2
                        val y1 = normalizedCoordinate(arguments, "y1", ChromeViewportHeight)
                        val x2 = normalizedCoordinate(arguments, "x2", ChromeViewportWidth)
                            ?: ChromeViewportWidth / 2
                        val y2 = normalizedCoordinate(arguments, "y2", ChromeViewportHeight)
                        if (y1 == null || y2 == null) {
                            errorResult("y1 and y2 are required, using 0..1000 screen coordinates.")
                        } else {
                            dispatchScroll(ensureStarted(), x1, y1, x2, y2)
                            updateCursor(x2, y2, 300)
                            delay(320)
                            captureResult("Scrolled browser.")
                        }
                    }
                }
                "text", "type" -> {
                    val text = arguments.optString("text")
                    if (text.isEmpty()) {
                        errorResult("Missing required 'text' argument.")
                    } else if (arguments.optString("selector").isNotBlank()) {
                        val result = evaluateBrowserJson(
                            ensureStarted(),
                            browserTypeScript(arguments.optString("selector"), text),
                        )
                        updateCursorFromBrowserResult(result)
                        delay(180)
                        captureResult("Typed in browser element.", result)
                    } else {
                        ensureStarted().send("Input.insertText", JSONObject().put("text", text))
                        delay(180)
                        captureResult("Typed in browser.")
                    }
                }
                "key" -> {
                    val key = arguments.optString("key").trim()
                    if (key.isBlank()) {
                        errorResult("Missing required 'key' argument.")
                    } else {
                        dispatchKey(ensureStarted(), key)
                        delay(220)
                        captureResult("Pressed $key in Chrome.")
                    }
                }
                "back" -> {
                    navigateHistory(ensureStarted(), -1)
                    delay(450)
                    captureResult("Went back in Chrome.")
                }
                "forward" -> {
                    navigateHistory(ensureStarted(), 1)
                    delay(450)
                    captureResult("Went forward in Chrome.")
                }
                "reload" -> {
                    ensureStarted().send("Page.reload", JSONObject().put("ignoreCache", false))
                    delay(550)
                    captureResult("Reloaded Chrome.")
                }
                "evaluate", "execute_js" -> {
                    val expression = arguments.optString("script").ifBlank {
                        arguments.optString("expression")
                    }
                    if (expression.isBlank()) {
                        errorResult("Missing required 'script' argument.")
                    } else {
                        browserJsonResult(evaluateBrowserValue(ensureStarted(), expression))
                    }
                }
                "get_text" -> browserJsonResult(
                    evaluateBrowserJson(ensureStarted(), browserGetTextScript(arguments.optString("selector"))),
                )
                "get_page_info" -> browserJsonResult(
                    evaluateBrowserJson(ensureStarted(), BrowserPageInfoScript),
                )
                "find_elements" -> {
                    val selector = arguments.optString("selector").trim()
                    if (selector.isBlank()) errorResult("find_elements requires 'selector'.")
                    else browserJsonResult(evaluateBrowserJson(ensureStarted(), browserFindElementsScript(selector)))
                }
                "get_readable" -> browserJsonResult(
                    evaluateBrowserJson(ensureStarted(), BrowserReadableScript),
                )
                "get_backbone" -> browserJsonResult(
                    evaluateBrowserJson(
                        ensureStarted(),
                        browserBackboneScript(arguments.optInt("max_depth", 5)),
                    ),
                )
                "list_resources", "get_resources" -> listResources(arguments)
                "get_resource", "get_resource_content" -> getResource(arguments)
                "list_requests" -> listRequests(arguments)
                "get_request" -> getRequest(arguments)
                "wait_for_dom_stable" -> {
                    val timeout = arguments.optInt("timeout", 5_000).coerceIn(1_000, 60_000)
                    browserJsonResult(
                        evaluateBrowserJson(
                            ensureStarted(),
                            browserWaitForDomStableScript(timeout),
                        ),
                    )
                }
                "screenshot" -> {
                    ensureStarted()
                    captureResult("Captured Chrome.")
                }
                "stop" -> stopLocked()
                else -> errorResult(
                    "Unsupported browser action '$action'."
                )
            }
        }.getOrElse { throwable ->
            diagnosticLogger.exception(
                category = "alpine_chrome",
                event = "action_failed",
                throwable = throwable,
                details = mapOf("action" to action),
            )
            errorResult(throwable.message ?: "Chrome action failed.")
        }
    }

    suspend fun startBrowser(): Result<Unit> = runCatching {
        operationMutex.withLock {
            ensureStarted()
            capturePreview()
        }
    }

    suspend fun shouldShowKeyboardAfterClick(x: Int, y: Int): Result<Boolean> = runCatching {
        operationMutex.withLock {
            ensureStarted()
            if (y in ChromeBrowserUiKeyboardMinY..ChromeBrowserUiKeyboardMaxY) {
                return@withLock true
            }
            delay(140)
            val result = cdpSession?.send(
                "Runtime.evaluate",
                JSONObject()
                    .put(
                        "expression",
                        """
                            (() => {
                              const element = document.activeElement;
                              return Boolean(
                                element &&
                                !element.disabled &&
                                !element.readOnly &&
                                (
                                  element.isContentEditable ||
                                  element.tagName === 'INPUT' ||
                                  element.tagName === 'TEXTAREA' ||
                                  element.tagName === 'SELECT'
                                )
                              );
                            })()
                        """.trimIndent(),
                    )
                    .put("returnByValue", true),
            )
            result
                ?.optJSONObject("result")
                ?.optBoolean("value", false)
                ?: false
        }
    }

    suspend fun stop(): Result<Unit> = runCatching {
        operationMutex.withLock { stopLocked() }
    }

    private suspend fun ensureStarted(): CdpSession {
        cdpSession?.takeIf { it.isOpen && isRunning() }?.let { return it }
        cdpSession?.close()
        cdpSession = null

        if (chromeProcess?.isAlive != true) {
            val command = """
                set -eu
                export DISPLAY=:99
                mkdir -p /root/.aether/chrome-profile /tmp/.X11-unix
                rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
                rm -f \
                    /root/.aether/chrome-profile/SingletonLock \
                    /root/.aether/chrome-profile/SingletonSocket \
                    /root/.aether/chrome-profile/SingletonCookie
                CHROME_BIN="${'$'}(command -v chromium-browser || command -v chromium)"
                test -n "${'$'}CHROME_BIN"
                cleanup() {
                    for pid in "${'$'}{CHROME_PID:-}" "${'$'}{NOVNC_PID:-}" "${'$'}{OPENBOX_PID:-}" "${'$'}{VNC_PID:-}"; do
                        if [ -n "${'$'}pid" ]; then kill "${'$'}pid" 2>/dev/null || true; fi
                    done
                }
                trap cleanup EXIT INT TERM
                Xvnc "${'$'}DISPLAY" \
                    -geometry ${ChromeViewportWidth}x${ChromeViewportHeight} \
                    -depth 24 \
                    -SecurityTypes None \
                    -localhost \
                    -rfbport $ChromeVncPort \
                    -AlwaysShared \
                    -extension MIT-SHM \
                    -nolock \
                    -ac &
                VNC_PID=${'$'}!
                for attempt in ${'$'}(seq 1 50); do
                    if [ -S /tmp/.X11-unix/X99 ]; then break; fi
                    sleep 0.1
                done
                test -S /tmp/.X11-unix/X99
                openbox &
                OPENBOX_PID=${'$'}!
                "${'$'}CHROME_BIN" \
                    --no-sandbox \
                    --disable-dev-shm-usage \
                    --disable-gpu \
                    --disable-gpu-compositing \
                    --disable-gpu-rasterization \
                    --no-first-run \
                    --no-default-browser-check \
                    --password-store=basic \
                    --remote-debugging-address=127.0.0.1 \
                    --remote-debugging-port=$ChromeCdpPort \
                    --remote-allow-origins=* \
                    --user-data-dir=/root/.aether/chrome-profile \
                    --window-size=$ChromeViewportWidth,$ChromeViewportHeight \
                    --window-position=0,0 \
                    --start-maximized \
                    --ozone-platform=x11 \
                    about:blank &
                CHROME_PID=${'$'}!
                CHROME_WINDOW_READY=
                for attempt in ${'$'}(seq 1 100); do
                    if ! kill -0 "${'$'}CHROME_PID" 2>/dev/null; then
                        wait "${'$'}CHROME_PID"
                    fi
                    if xprop -root _NET_CLIENT_LIST 2>/dev/null | grep -q '0x'; then
                        CHROME_WINDOW_READY=1
                        break
                    fi
                    sleep 0.1
                done
                test -n "${'$'}CHROME_WINDOW_READY"
                websockify --web=/usr/share/novnc 127.0.0.1:$ChromeNoVncPort 127.0.0.1:$ChromeVncPort &
                NOVNC_PID=${'$'}!
                wait "${'$'}CHROME_PID"
            """.trimIndent()
            chromeProcess = alpineRuntime.startManagedProcess(
                command = command,
                workingDirectory = alpineRuntime.homeDirectory,
                redirectErrorStream = true,
            ).also(::drainProcessOutput)
        }

        waitForNoVnc()
        val target = waitForPageTarget()
        val session = CdpSession(target.getString("webSocketDebuggerUrl"))
        session.connect()
        session.onEvent = { method, params, eventSessionId ->
            handleCdpEvent(session, method, params, eventSessionId)
        }
        session.send("Page.enable", JSONObject())
        session.send("Runtime.enable", JSONObject())
        session.send("Network.enable", networkEnableParams())
        runCatching {
            session.send("Network.setCacheDisabled", JSONObject().put("cacheDisabled", false))
        }
        runCatching {
            session.send(
                "Target.setAutoAttach",
                JSONObject()
                    .put("autoAttach", true)
                    // Children stay paused until Network.enable lands on their session,
                    // otherwise the first requests of an OOPIF/worker are never observed.
                    .put("waitForDebuggerOnStart", true)
                    .put("flatten", true),
            )
        }
        session.send(
            "Emulation.setDeviceMetricsOverride",
            JSONObject()
                .put("width", ChromeViewportWidth)
                .put("height", ChromeViewportHeight)
                .put("deviceScaleFactor", 1)
                .put("mobile", false),
        )
        networkRegistry.clear()
        cdpSession = session
        _displayState.value = _displayState.value.copy(
            isActive = true,
            status = "Chrome is running.",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return session
    }

    private suspend fun waitForNoVnc() {
        val deadline = System.currentTimeMillis() + ChromeStartupTimeoutMillis
        var lastError = ""
        while (System.currentTimeMillis() < deadline) {
            if (chromeProcess?.isAlive != true) {
                delay(100)
                error(
                    chromeProcessFailureMessage(
                        "Chromium display exited before noVNC became available."
                    )
                )
            }
            runCatching {
                requestNoVncPage()
                requestVncPort()
            }.onSuccess {
                return
            }.onFailure {
                lastError = it.message.orEmpty()
            }
            delay(200)
        }
        error(lastError.ifBlank { "Timed out waiting for the Chrome noVNC viewer." })
    }

    private suspend fun requestNoVncPage() = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$ChromeNoVncPort/vnc_lite.html")
                .build()
        ).execute().use { response ->
            require(response.isSuccessful) { "noVNC HTTP ${response.code}" }
        }
    }

    private suspend fun requestVncPort() = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress("127.0.0.1", ChromeVncPort),
                1_000,
            )
        }
    }

    private suspend fun waitForPageTarget(): JSONObject {
        val deadline = System.currentTimeMillis() + ChromeStartupTimeoutMillis
        var lastError = ""
        while (System.currentTimeMillis() < deadline) {
            if (chromeProcess?.isAlive != true) {
                delay(100)
                error(chromeProcessFailureMessage("Chromium exited before CDP became available."))
            }
            runCatching {
                requestJsonArray("http://127.0.0.1:$ChromeCdpPort/json/list")
            }.onSuccess { targets ->
                for (index in 0 until targets.length()) {
                    val target = targets.optJSONObject(index) ?: continue
                    if (
                        target.optString("type") == "page" &&
                        target.optString("webSocketDebuggerUrl").isNotBlank()
                    ) {
                        return target
                    }
                }
            }.onFailure { lastError = it.message.orEmpty() }
            delay(200)
        }
        error(lastError.ifBlank { "Timed out waiting for Chromium CDP." })
    }

    private fun chromeProcessFailureMessage(prefix: String): String {
        val logTail = runCatching {
            File(cacheDirectory, "chromium.log")
                .takeIf(File::isFile)
                ?.readText()
                ?.trim()
                ?.takeLast(1_200)
                .orEmpty()
        }.getOrDefault("")
        return if (logTail.isBlank()) {
            "$prefix Check the Alpine Chrome installation."
        } else {
            "$prefix\n$logTail"
        }
    }

    private suspend fun captureResult(
        message: String,
        extra: JSONObject = JSONObject(),
    ): String {
        val screenshot = capturePreview()
        val persistedScreenshot = File(
            captureDirectory,
            "chrome-${System.currentTimeMillis()}-${System.nanoTime()}.jpg",
        )
        screenshot.first.copyTo(persistedScreenshot, overwrite = true)
        val pageInfo = pageInfo()
        return JSONObject().apply {
            put("ok", true)
            put("stdout", message)
            put("url", pageInfo.first)
            put("title", pageInfo.second)
            put("width", ChromeViewportWidth)
            put("height", ChromeViewportHeight)
            put("screenshot_path", persistedScreenshot.absolutePath)
            put("preview_path", persistedScreenshot.absolutePath)
            put("screenshot_mime_type", "image/jpeg")
            put("screenshot_base64", screenshot.second)
            _displayState.value.cursorX?.let { put("cursor_x", it) }
            _displayState.value.cursorY?.let { put("cursor_y", it) }
            extra.keys().forEach { key -> put(key, extra.opt(key)) }
        }.toString()
    }

    private suspend fun capturePreview(): Pair<File, String> {
        val session = cdpSession ?: error("Chrome CDP is not connected.")
        val result = session.send(
            "Page.captureScreenshot",
            JSONObject()
                .put("format", "jpeg")
                .put("quality", ChromeScreenshotQuality)
                .put("fromSurface", true)
                .put("captureBeyondViewport", false),
        )
        val encoded = result.optString("data")
        require(encoded.isNotBlank()) { "Chrome returned an empty screenshot." }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val previewFile = File(cacheDirectory, "chrome-preview.jpg")
        val stagingFile = File(cacheDirectory, "chrome-preview.tmp")
        stagingFile.writeBytes(bytes)
        if (previewFile.exists()) previewFile.delete()
        require(stagingFile.renameTo(previewFile)) { "Unable to update Chrome preview." }
        _displayState.value = _displayState.value.copy(
            isActive = true,
            latestPreviewPath = previewFile.absolutePath,
            latestWorkspacePath = "",
            lastUpdatedMillis = System.currentTimeMillis(),
            status = "Chrome is running.",
        )
        return previewFile to encoded
    }

    private suspend fun pageInfo(): Pair<String, String> {
        val result = cdpSession?.send(
            "Runtime.evaluate",
            JSONObject()
                .put("expression", "JSON.stringify({url: location.href, title: document.title})")
                .put("returnByValue", true),
        ) ?: return "" to ""
        val raw = result.optJSONObject("result")?.optString("value").orEmpty()
        val value = runCatching { JSONObject(raw) }.getOrNull()
        return value?.optString("url").orEmpty() to value?.optString("title").orEmpty()
    }

    private suspend fun evaluateBrowserJson(session: CdpSession, expression: String): JSONObject {
        val value = evaluateBrowserValue(session, expression)
        return when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrElse {
                JSONObject().put("ok", true).put("result", value)
            }
            else -> JSONObject().put("ok", true).put("result", value ?: JSONObject.NULL)
        }
    }

    private suspend fun evaluateBrowserValue(session: CdpSession, expression: String): Any? {
        val result = session.send(
            "Runtime.evaluate",
            JSONObject()
                .put("expression", expression)
                .put("returnByValue", true)
                .put("awaitPromise", true),
        )
        result.optJSONObject("exceptionDetails")?.let { exception ->
            error(exception.optString("text").ifBlank { "JavaScript evaluation failed." })
        }
        return result.optJSONObject("result")?.opt("value")
    }

    private suspend fun browserJsonResult(payload: Any?): String {
        val result = when (payload) {
            is JSONObject -> JSONObject(payload.toString())
            else -> JSONObject().put("ok", true).put("result", payload ?: JSONObject.NULL)
        }
        if (!result.has("ok")) result.put("ok", true)
        val info = pageInfo()
        if (!result.has("url")) result.put("url", info.first)
        if (!result.has("title")) result.put("title", info.second)
        if (!result.has("stdout")) {
            result.put("stdout", result.optString("text").ifBlank { result.toString() })
        }
        return result.toString()
    }

    private suspend fun dispatchTap(session: CdpSession, x: Int, y: Int) {
        session.send(
            "Input.dispatchMouseEvent",
            JSONObject()
                .put("type", "mousePressed")
                .put("x", x)
                .put("y", y)
                .put("button", "left")
                .put("clickCount", 1),
        )
        session.send(
            "Input.dispatchMouseEvent",
            JSONObject()
                .put("type", "mouseReleased")
                .put("x", x)
                .put("y", y)
                .put("button", "left")
                .put("clickCount", 1),
        )
    }

    private suspend fun dispatchScroll(
        session: CdpSession,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) {
        session.send(
            "Input.dispatchMouseEvent",
            JSONObject()
                .put("type", "mouseWheel")
                .put("x", x2)
                .put("y", y2)
                .put("deltaX", x1 - x2)
                .put("deltaY", y1 - y2),
        )
    }

    private suspend fun dispatchKey(session: CdpSession, rawKey: String) {
        val key = ChromeKey.from(rawKey)
        val base = JSONObject()
            .put("key", key.key)
            .put("code", key.code)
            .put("windowsVirtualKeyCode", key.virtualKeyCode)
            .put("nativeVirtualKeyCode", key.virtualKeyCode)
        session.send("Input.dispatchKeyEvent", JSONObject(base.toString()).put("type", "keyDown"))
        session.send("Input.dispatchKeyEvent", JSONObject(base.toString()).put("type", "keyUp"))
    }

    private suspend fun navigateHistory(session: CdpSession, offset: Int) {
        val history = session.send("Page.getNavigationHistory", JSONObject())
        val entries = history.optJSONArray("entries") ?: return
        val currentIndex = history.optInt("currentIndex", -1)
        val targetIndex = currentIndex + offset
        if (targetIndex !in 0 until entries.length()) return
        val entryId = entries.optJSONObject(targetIndex)?.optInt("id", -1) ?: -1
        if (entryId >= 0) {
            session.send("Page.navigateToHistoryEntry", JSONObject().put("entryId", entryId))
        }
    }

    private fun updateCursor(x: Int, y: Int, durationMillis: Int) {
        _displayState.value = _displayState.value.copy(
            cursorX = x,
            cursorY = y,
            cursorAnimationDurationMillis = durationMillis,
            lastUpdatedMillis = System.currentTimeMillis(),
        )
    }

    private fun updateCursorFromBrowserResult(result: JSONObject) {
        if (!result.has("cursor_x") || !result.has("cursor_y")) return
        updateCursor(
            x = result.optInt("cursor_x").coerceIn(0, ChromeViewportWidth),
            y = result.optInt("cursor_y").coerceIn(0, ChromeViewportHeight),
            durationMillis = result.optInt("cursor_animation_duration_ms", 220),
        )
    }

    private fun statusResult(): String = JSONObject().apply {
        put("ok", true)
        put("running", isRunning())
        put("width", ChromeViewportWidth)
        put("height", ChromeViewportHeight)
        put("network_records", networkRegistry.size)
        put("network_capture", isRunning())
        put("stdout", if (isRunning()) "Chrome is running." else "Chrome is stopped.")
    }.toString()

    private fun stopLocked(): String {
        runCatching { cdpSession?.sendBlocking("Browser.close", JSONObject()) }
        cdpSession?.close()
        cdpSession = null
        networkRegistry.clear()
        chromeProcess?.let { process ->
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        chromeProcess = null
        _displayState.value = _displayState.value.copy(
            isActive = false,
            cursorX = null,
            cursorY = null,
            status = "Chrome is stopped.",
            lastUpdatedMillis = System.currentTimeMillis(),
        )
        return JSONObject()
            .put("ok", true)
            .put("stdout", "Chrome stopped.")
            .toString()
    }

    private fun isRunning(): Boolean = chromeProcess?.isAlive == true && cdpSession?.isOpen == true

    private fun drainProcessOutput(process: Process) {
        scope.launch {
            runCatching {
                FileOutputStream(File(cacheDirectory, "chromium.log"), false).use { output ->
                    process.inputStream.use { input -> input.copyTo(output) }
                }
            }
            if (chromeProcess === process && !process.isAlive) {
                cdpSession?.close()
                cdpSession = null
                _displayState.value = _displayState.value.copy(
                    isActive = false,
                    status = "Chrome exited.",
                    lastUpdatedMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun requestJsonArray(url: String): JSONArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.isSuccessful) { "CDP HTTP ${response.code}" }
            JSONArray(response.body?.string().orEmpty())
        }
    }

    private fun normalizeUrl(rawValue: String): String {
        val value = rawValue.trim()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("http://", ignoreCase = true) -> value
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("about:", ignoreCase = true) -> value
            else -> "https://$value"
        }
    }

    private fun normalizedCoordinate(
        arguments: JSONObject,
        key: String,
        size: Int,
    ): Int? {
        val value = arguments.optDouble(key, Double.NaN)
        return value.takeIf { !it.isNaN() }
            ?.let { (it.coerceIn(0.0, 1000.0) * size / 1000.0).toInt() }
    }

    private fun errorResult(message: String): String = JSONObject()
        .put("ok", false)
        .put("errmsg", message)
        .toString()

    private fun networkEnableParams(): JSONObject = JSONObject()
        .put("maxTotalBufferSize", NetworkMaxTotalBufferBytes)
        .put("maxResourceBufferSize", NetworkMaxResourceBufferBytes)
        .put("maxPostDataSize", NetworkMaxPostDataBytes)

    private fun handleCdpEvent(
        session: CdpSession,
        method: String,
        params: JSONObject,
        sessionId: String,
    ) {
        when (method) {
            "Network.requestWillBeSent" -> networkRegistry.onRequestWillBeSent(params, sessionId)
            "Network.responseReceived" -> networkRegistry.onResponseReceived(params, sessionId)
            "Network.loadingFinished" -> networkRegistry.onLoadingFinished(params, sessionId)
            "Network.loadingFailed" -> networkRegistry.onLoadingFailed(params, sessionId)
            "Network.requestServedFromCache" ->
                networkRegistry.onRequestServedFromCache(params, sessionId)
            "Target.attachedToTarget" -> attachChildTarget(session, params)
            "Target.detachedFromTarget" -> session.forgetChildSession(params.optString("sessionId"))
        }
    }

    private fun attachChildTarget(session: CdpSession, params: JSONObject) {
        val childSessionId = params.optString("sessionId")
        val targetType = params.optJSONObject("targetInfo")?.optString("type").orEmpty()
        if (childSessionId.isBlank()) return
        scope.launch {
            try {
                runCatching {
                    session.send("Network.enable", networkEnableParams(), childSessionId)
                }
                if (targetType == "iframe") {
                    runCatching { session.send("Page.enable", JSONObject(), childSessionId) }
                    val frameTree = runCatching {
                        session.send("Page.getFrameTree", JSONObject(), childSessionId)
                    }.getOrNull()?.optJSONObject("frameTree")
                    if (frameTree != null) {
                        // Register every frame in this session, not just the root: a nested
                        // frame inside an OOPIF must resolve to this session, not to main.
                        registerFrameTree(session, childSessionId, frameTree)
                    }
                }
            } finally {
                // The target was attached with waitForDebuggerOnStart, so it must be
                // resumed even when enabling a domain above failed.
                runCatching {
                    session.send("Runtime.runIfWaitingForDebugger", JSONObject(), childSessionId)
                }
            }
        }
    }

    private fun registerFrameTree(session: CdpSession, sessionId: String, frameTree: JSONObject) {
        val frameId = frameTree.optJSONObject("frame")?.optString("id").orEmpty()
        if (frameId.isNotBlank()) {
            session.rememberFrameSession(frameId, sessionId)
            session.rememberChildSessionRoot(sessionId, frameId)
        }
        val children = frameTree.optJSONArray("childFrames") ?: return
        for (index in 0 until children.length()) {
            val child = children.optJSONObject(index)?.optJSONObject("frameTree") ?: continue
            registerFrameTree(session, sessionId, child)
        }
    }

    private suspend fun listResources(arguments: JSONObject): String {
        val session = ensureStarted()
        val limit = arguments.optInt("limit", BrowserDefaultResourceLimit).coerceIn(1, 5_000)
        val frameIdFilter = arguments.optString("frame_id").trim()
        val urlContains = arguments.optString("url_contains").trim()
        val frames = collectFrameEntries(session)
        val selectedFrames = if (frameIdFilter.isBlank()) {
            frames
        } else {
            val allowedFrameIds = frameClosure(frames, frameIdFilter)
            frames.filter { it.frameId in allowedFrameIds }
        }
        val networkByUrl = networkRegistry.latestByUrl(
            selectedFrames.flatMap { frame -> frame.resources }.map { it.url }.toSet(),
        )
        val resources = selectedFrames
            .flatMap { frame ->
                frame.resources.map { resource ->
                    val networkRecord = networkByUrl[resource.url]
                    BrowserResourceItem(
                        url = resource.url,
                        type = resource.type,
                        mimeType = networkRecord?.mimeType?.takeIf { it.isNotBlank() }
                            ?: resource.mimeType,
                        contentSize = resource.contentSize,
                        failed = resource.failed,
                        canceled = resource.canceled,
                        frameId = frame.frameId,
                        requestId = networkRecord?.requestId.orEmpty(),
                        status = networkRecord?.status,
                        source = if (networkRecord == null) "tree" else "tree+network",
                    )
                }
            }
            .filter { item ->
                urlContains.isBlank() || item.url.contains(urlContains, ignoreCase = true)
            }
        val visible = resources.take(limit)
        val truncated = resources.size > visible.size
        val savedTo = if (truncated) {
            val fullPayload = JSONObject()
                .put("frame_count", selectedFrames.size)
                .put("frames", framesToJson(selectedFrames))
                .put("resources", resourcesToJson(resources))
            writeGuestArtifact(
                "resources",
                "resource-tree-${System.currentTimeMillis()}.json",
                fullPayload.toString().toByteArray(Charsets.UTF_8),
            ).orEmpty()
        } else {
            ""
        }
        return BrowserDataPayload.render(
            JSONObject().apply {
                put("ok", true)
                put("count", visible.size)
                put("frame_count", selectedFrames.size)
                put("frames", framesToJson(selectedFrames))
                put("resources", resourcesToJson(visible))
                put("truncated", truncated)
                put("saved_to", savedTo)
                put("save_failed", truncated && savedTo.isEmpty())
                put(
                    "summary",
                    "Enumerated ${resources.size} resources across ${selectedFrames.size} frames.",
                )
            },
        )
    }

    private suspend fun collectFrameEntries(session: CdpSession): List<BrowserFrameEntry> {
        val entries = mutableListOf<BrowserFrameEntry>()
        val mainTree = session.send(
            "Page.getResourceTree",
            JSONObject(),
            timeoutMillis = ChromeDataCommandTimeoutMillis,
        )
        entries += BrowserResourceTreeParser.parse(
            mainTree.optJSONObject("frameTree") ?: JSONObject(),
            "",
            true,
        )
        session.childSessions().forEach { childSessionId ->
            val childTree = runCatching {
                session.send(
                    "Page.getResourceTree",
                    JSONObject(),
                    childSessionId,
                    ChromeDataCommandTimeoutMillis,
                )
            }.getOrNull() ?: return@forEach
            entries += BrowserResourceTreeParser.parse(
                childTree.optJSONObject("frameTree") ?: JSONObject(),
                childSessionId,
                false,
            )
        }
        return entries
    }

    private fun frameClosure(
        frames: List<BrowserFrameEntry>,
        rootFrameId: String,
    ): Set<String> {
        val result = mutableSetOf(rootFrameId)
        var changed = true
        while (changed) {
            changed = false
            frames.forEach { frame ->
                if (frame.parentFrameId in result && result.add(frame.frameId)) {
                    changed = true
                }
            }
        }
        return result
    }

    private fun framesToJson(frames: List<BrowserFrameEntry>): JSONArray {
        val array = JSONArray()
        frames.forEach { frame ->
            array.put(
                JSONObject()
                    .put("frame_id", frame.frameId)
                    .put("parent_frame_id", frame.parentFrameId)
                    .put("url", frame.url)
                    .put("name", frame.name)
                    .put("mime_type", frame.mimeType)
                    .put("security_origin", frame.securityOrigin)
                    .put("is_main_frame", frame.isMainFrame)
                    .put("session_id", frame.sessionId)
                    .put("resource_count", frame.resources.size)
            )
        }
        return array
    }

    private fun resourcesToJson(resources: List<BrowserResourceItem>): JSONArray {
        val array = JSONArray()
        resources.forEach { resource ->
            array.put(
                JSONObject()
                    .put("url", resource.url)
                    .put("type", resource.type)
                    .put("mime_type", resource.mimeType)
                    .put("content_size", resource.contentSize)
                    .put("failed", resource.failed)
                    .put("canceled", resource.canceled)
                    .put("frame_id", resource.frameId)
                    .put("request_id", resource.requestId)
                    .put("status", resource.status ?: JSONObject.NULL)
                    .put("source", resource.source)
            )
        }
        return array
    }

    private suspend fun getResource(arguments: JSONObject): String {
        val urlArgument = arguments.optString("url").trim()
        val requestIdArgument = arguments.optString("request_id").trim()
        if (urlArgument.isBlank() && requestIdArgument.isBlank()) {
            return resourceError("MISSING_TARGET", "Provide 'url' or 'request_id'.")
        }
        val session = ensureStarted()
        val record = if (requestIdArgument.isNotBlank()) {
            networkRegistry.findById(requestIdArgument)
        } else {
            networkRegistry.findLatestByUrl(urlArgument)
        }
        val url = urlArgument.ifBlank { record?.url.orEmpty() }
        val frameIdArgument = arguments.optString("frame_id").trim()
        val encoding = normalizeEncoding(arguments.optString("encoding", "auto"))
        val inline = arguments.optBoolean("inline", true)
        val inlineMaxBytes = arguments.optInt("inline_max_bytes", BrowserInlineBase64Limit)
            .coerceAtLeast(0)
        val maxBytes = arguments.optInt("max_bytes", 0).coerceAtLeast(0)
        val allowNetwork = arguments.optBoolean("allow_network", false)
        var resolvedFrameId = frameIdArgument.ifBlank { record?.frameId.orEmpty() }
        var failureCode = ""
        var failureMessage = ""
        var failureTimedOut = false
        var networkFetchBlocked = false

        if (record != null) {
            val body = fetchResponseBody(
                session = session,
                record = record,
                encoding = encoding,
                inline = inline,
                inlineMaxBytes = inlineMaxBytes,
                maxBytes = maxBytes,
            )
            if (body.optBoolean("ok")) {
                return BrowserDataPayload.render(
                    body
                        .put("url", url)
                        .put("frame_id", resolvedFrameId),
                )
            }
            failureCode = body.optString("code")
            failureMessage = body.optString("errmsg")
            failureTimedOut = failureCode == "TIMEOUT"
        }

        if (url.isNotBlank()) {
            if (resolvedFrameId.isBlank()) resolvedFrameId = session.mainFrameId()
            val contentSessionId = session.sessionForFrame(resolvedFrameId)
            val contentResult = session.trySend(
                "Page.getResourceContent",
                JSONObject()
                    .put("frameId", resolvedFrameId)
                    .put("url", url),
                contentSessionId,
                ChromeDataCommandTimeoutMillis,
            )
            val content = contentResult.getOrNull()
            if (content != null) {
                val payload = buildResourcePayload(
                    session = session,
                    source = "page-resource",
                    url = url,
                    requestId = record?.requestId ?: requestIdArgument,
                    frameId = resolvedFrameId,
                    record = record,
                    text = content.optString("content"),
                    base64Encoded = content.optBoolean("base64Encoded", false),
                    encoding = encoding,
                    inline = inline,
                    inlineMaxBytes = inlineMaxBytes,
                    maxBytes = maxBytes,
                )
                return if (payload.optBoolean("ok")) {
                    BrowserDataPayload.render(payload)
                } else {
                    payload.toString()
                }
            }
            val contentFailure = contentResult.exceptionOrNull()
            failureTimedOut = failureTimedOut || contentFailure is TimeoutCancellationException
            val contentFailureMessage = contentFailure?.message.orEmpty()
            val contentFailureCode = specificCdpErrorCode(contentFailureMessage)
            if (contentFailureCode.isNotBlank()) failureCode = contentFailureCode
            failureMessage = contentFailureMessage
        }

        if (url.isNotBlank()) {
            if (!allowNetwork) {
                networkFetchBlocked = true
            } else {
                if (resolvedFrameId.isBlank()) resolvedFrameId = session.mainFrameId()
                val loadResult = session.trySend(
                    "Network.loadNetworkResource",
                    JSONObject()
                        .put("frameId", resolvedFrameId)
                        .put("url", url)
                        .put(
                            "options",
                            JSONObject()
                                .put("disableCache", false)
                                .put("includeCredentials", true),
                        ),
                    timeoutMillis = ChromeDataCommandTimeoutMillis,
                )
                val loaded = loadResult.getOrNull()
                if (loaded != null) {
                    val resource = loaded.optJSONObject("resource")
                    var text = ""
                    var base64Encoded = true
                    var hasContent = false
                    if (resource != null && resource.has("content")) {
                        text = resource.optString("content")
                        hasContent = true
                    } else {
                        val streamHandle = resource?.optString("stream").orEmpty()
                        if (streamHandle.isNotBlank()) {
                            val streamed = readStreamBody(session, streamHandle)
                            session.trySend(
                                "IO.close",
                                JSONObject().put("handle", streamHandle),
                                timeoutMillis = ChromeDataCommandTimeoutMillis,
                            )
                            val streamText = streamed.getOrNull()
                            if (streamText != null) {
                                text = streamText
                                base64Encoded = true
                                hasContent = true
                            } else {
                                val readFailure = streamed.exceptionOrNull()
                                failureTimedOut = failureTimedOut ||
                                    readFailure is TimeoutCancellationException
                                failureMessage = readFailure?.message.orEmpty()
                                val readFailureCode = specificCdpErrorCode(failureMessage)
                                if (readFailureCode.isNotBlank()) failureCode = readFailureCode
                            }
                        } else {
                            failureMessage = "Chrome returned no network resource content."
                        }
                    }
                    if (hasContent) {
                        val payload = buildResourcePayload(
                            session = session,
                            source = "network-load",
                            url = url,
                            requestId = record?.requestId ?: requestIdArgument,
                            frameId = resolvedFrameId,
                            record = record,
                            text = text,
                            base64Encoded = base64Encoded,
                            encoding = encoding,
                            inline = inline,
                            inlineMaxBytes = inlineMaxBytes,
                            maxBytes = maxBytes,
                        )
                        return if (payload.optBoolean("ok")) {
                            BrowserDataPayload.render(payload)
                        } else {
                            payload.toString()
                        }
                    }
                } else {
                    val loadFailure = loadResult.exceptionOrNull()
                    failureTimedOut = failureTimedOut || loadFailure is TimeoutCancellationException
                    failureMessage = loadFailure?.message.orEmpty()
                    val loadFailureCode = specificCdpErrorCode(failureMessage)
                    if (loadFailureCode.isNotBlank()) failureCode = loadFailureCode
                }
            }
        }

        val code = when {
            failureTimedOut -> "TIMEOUT"
            failureCode.isNotBlank() -> failureCode
            networkFetchBlocked -> "NETWORK_FETCH_DISABLED"
            failureMessage.isNotBlank() -> cdpErrorCode(failureMessage)
            else -> "NOT_OBSERVED"
        }
        return resourceError(
            code,
            failureMessage.ifBlank { "Unable to read the requested resource." },
        )
    }

    private suspend fun fetchResponseBody(
        session: CdpSession,
        record: BrowserRequestRecord,
        encoding: String,
        inline: Boolean,
        inlineMaxBytes: Int,
        maxBytes: Int,
    ): JSONObject {
        val result = session.trySend(
            "Network.getResponseBody",
            JSONObject().put("requestId", record.requestId),
            record.sessionId,
            ChromeDataCommandTimeoutMillis,
        )
        val failure = result.exceptionOrNull()
        if (failure != null) {
            return resourceErrorObject(
                errorCodeFor(failure),
                failure.message.orEmpty().ifBlank { "Chrome did not return a response body." },
            )
        }
        val body = result.getOrThrow()
        return buildResourcePayload(
            session = session,
            source = "network-body",
            url = record.url,
            requestId = record.requestId,
            frameId = record.frameId,
            record = record,
            text = body.optString("body"),
            base64Encoded = body.optBoolean("base64Encoded", false),
            encoding = encoding,
            inline = inline,
            inlineMaxBytes = inlineMaxBytes,
            maxBytes = maxBytes,
        )
    }

    private suspend fun readStreamBody(session: CdpSession, handle: String): Result<String> {
        val chunks = mutableListOf<ByteArray>()
        var eof = false
        var streamOffset = 0
        while (!eof) {
            // `size` is optional in the CDP contract ("if not specified, the whole stream is
            // read"); offset is tracked so a partial read can never replay the same bytes.
            val readResult = session.trySend(
                "IO.read",
                JSONObject().put("handle", handle).put("offset", streamOffset),
                timeoutMillis = ChromeDataCommandTimeoutMillis,
            )
            val read = readResult.getOrNull()
                ?: return Result.failure(
                    readResult.exceptionOrNull()
                        ?: IllegalStateException("Chrome stream read failed."),
                )
            val data = read.optString("data")
            val chunk = if (read.optBoolean("base64Encoded", false)) {
                runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrElse {
                    return Result.failure(it)
                }
            } else {
                data.toByteArray(Charsets.UTF_8)
            }
            chunks += chunk
            streamOffset += chunk.size
            eof = read.optBoolean("eof", true) || data.isEmpty()
        }
        var size = 0
        chunks.forEach { size += it.size }
        val bytes = ByteArray(size)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return Result.success(Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private suspend fun buildResourcePayload(
        session: CdpSession,
        source: String,
        url: String,
        requestId: String,
        frameId: String,
        record: BrowserRequestRecord?,
        text: String,
        base64Encoded: Boolean,
        encoding: String,
        inline: Boolean,
        inlineMaxBytes: Int,
        maxBytes: Int,
    ): JSONObject {
        val decoded = if (base64Encoded) {
            runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrElse {
                return resourceErrorObject(
                    "BODY_EVICTED",
                    it.message ?: "Chrome returned invalid base64 content.",
                )
            }
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        val truncated = maxBytes > 0 && decoded.size > maxBytes
        val bytes = if (truncated) decoded.copyOf(maxBytes) else decoded
        val digest = sha256Hex(bytes)
        val mimeType = resolveResourceMimeType(session, record, url, frameId)
        val fileName = safeArtifactFileName(url, mimeType)
        val savedTo = writeGuestArtifact(
            "resources",
            "${digest.take(16)}-$fileName",
            bytes,
        )
        val canInline = inline && bytes.size <= inlineMaxBytes
        if (savedTo == null && !canInline) {
            return resourceErrorObject(
                "SAVE_FAILED",
                "Unable to write the resource artifact.",
            )
        }
        val useText = encoding == "text" || (encoding == "auto" && isTextMimeType(mimeType))
        val summary = when (source) {
            "network-body" -> "Read ${bytes.size} bytes from the captured response body."
            "page-resource" -> "Read ${bytes.size} bytes from the page resource cache."
            else -> "Fetched ${bytes.size} bytes from the network."
        }
        return JSONObject().apply {
            put("ok", true)
            put("source", source)
            put("url", url)
            put("request_id", requestId)
            put("frame_id", frameId)
            put("mime_type", mimeType)
            put("encoding", if (useText) "text" else "base64")
            put("bytes", bytes.size)
            put("sha256", digest)
            put("truncated", truncated)
            put("saved_to", savedTo ?: "")
            put("save_failed", savedTo == null)
            if (canInline) {
                if (useText) {
                    put("text", String(bytes, Charsets.UTF_8))
                    put("base64", JSONObject.NULL)
                } else {
                    put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("text", JSONObject.NULL)
                }
            }
            put("summary", summary)
        }
    }

    private suspend fun resolveResourceMimeType(
        session: CdpSession,
        record: BrowserRequestRecord?,
        url: String,
        frameId: String,
    ): String {
        val recordMimeType = record?.mimeType.orEmpty()
        if (recordMimeType.isNotBlank()) return recordMimeType
        val treeMimeType = runCatching {
            lookupTreeMimeType(session, frameId, url)
        }.getOrDefault("")
        if (treeMimeType.isNotBlank()) return treeMimeType
        val headerMimeType = record?.responseHeaders.orEmpty().entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()
        if (headerMimeType.isNotBlank()) return headerMimeType
        return mimeTypeFromUrl(url)
    }

    private suspend fun lookupTreeMimeType(
        session: CdpSession,
        frameId: String,
        url: String,
    ): String {
        if (url.isBlank()) return ""
        val frames = collectFrameEntries(session)
        val candidates = if (frameId.isBlank()) {
            frames
        } else {
            frames.filter { it.frameId == frameId }.ifEmpty { frames }
        }
        return candidates
            .asSequence()
            .flatMap { it.resources.asSequence() }
            .firstOrNull { it.url == url && it.mimeType.isNotBlank() }
            ?.mimeType
            .orEmpty()
    }

    private fun listRequests(arguments: JSONObject): String {
        val sinceSeq = arguments.optLong("since_seq", 0L)
        val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
        val statusFilter = arguments.optInt("status", 0).takeIf { it != 0 }
        val filter = BrowserRequestFilter(
            urlContains = arguments.optString("url_contains").trim(),
            method = arguments.optString("method").trim(),
            status = statusFilter,
            resourceType = arguments.optString("resource_type").trim(),
            mimePrefix = arguments.optString("mime_prefix").trim(),
        )
        val includeHeaders = arguments.optBoolean("include_headers", false)
        val includeSensitive = arguments.optBoolean("include_sensitive", false)
        val matched = networkRegistry.list(sinceSeq, filter, Int.MAX_VALUE)
        val visible = matched.take(limit)
        val requests = JSONArray()
        visible.forEach { record ->
            val durationMillis = record.durationMillis ?: 0L
            val item = JSONObject()
                .put("seq", record.seq)
                .put("request_id", record.requestId)
                .put("url", record.url)
                .put("method", record.method)
                .put("resource_type", record.resourceType)
                .put("status", record.status ?: JSONObject.NULL)
                .put("status_text", record.statusText)
                .put("mime_type", record.mimeType)
                .put("started_at_ms", record.startedAtMillis)
                .put("duration_ms", durationMillis)
                .put("encoded_bytes", record.encodedDataLength ?: JSONObject.NULL)
                .put("from_disk_cache", record.fromDiskCache)
                .put("from_service_worker", record.fromServiceWorker)
                .put("failed", record.failed)
                .put("error_text", record.errorText)
            if (includeHeaders) {
                item.put(
                    "request_headers",
                    BrowserHeaderRedaction.toJson(record.requestHeaders, includeSensitive),
                )
                item.put(
                    "response_headers",
                    BrowserHeaderRedaction.toJson(record.responseHeaders, includeSensitive),
                )
            }
            requests.put(item)
        }
        return BrowserDataPayload.render(
            JSONObject().apply {
                put("ok", true)
                put("total", matched.size)
                put("count", visible.size)
                put("next_seq", visible.maxOfOrNull { it.seq } ?: sinceSeq)
                put("truncated", matched.size > visible.size)
                put("requests", requests)
                put("summary", "Listed ${visible.size} of ${matched.size} tracked requests.")
            },
        )
    }

    private suspend fun getRequest(arguments: JSONObject): String {
        val urlArgument = arguments.optString("url").trim()
        val requestIdArgument = arguments.optString("request_id").trim()
        if (urlArgument.isBlank() && requestIdArgument.isBlank()) {
            return resourceError("MISSING_TARGET", "Provide 'url' or 'request_id'.")
        }
        val record = if (requestIdArgument.isNotBlank()) {
            networkRegistry.findById(requestIdArgument)
        } else {
            networkRegistry.findLatestByUrl(urlArgument)
        } ?: return resourceError(
            "NOT_OBSERVED",
            "No tracked request matches the requested target.",
        )
        val includeRequestHeaders = arguments.optBoolean("include_request_headers", true)
        val includeResponseHeaders = arguments.optBoolean("include_response_headers", true)
        val includePostData = arguments.optBoolean("include_post_data", false)
        val includeResponseBody = arguments.optBoolean("include_response_body", false)
        val includeSensitive = arguments.optBoolean("include_sensitive", false)
        val inline = arguments.optBoolean("inline", true)
        val inlineMaxBytes = arguments.optInt("inline_max_bytes", BrowserInlineBase64Limit)
            .coerceAtLeast(0)
        val maxBytes = arguments.optInt("max_bytes", 0).coerceAtLeast(0)
        val bodyMode = normalizeEncoding(arguments.optString("body_mode", "auto"))
        val needsPostData = includePostData && record.postData.isBlank() && record.hasPostData
        val session = if (includeResponseBody || needsPostData) ensureStarted() else null

        var postData = record.postData
        var postDataTruncated = false
        if (session != null && needsPostData) {
            val fetched = session.trySend(
                "Network.getRequestPostData",
                JSONObject().put("requestId", record.requestId),
                record.sessionId,
            )
            fetched.getOrNull()?.let { result ->
                postData = result.optString("postData")
            }
        }
        if (postData.length > NetworkMaxPostDataBytes) {
            postData = postData.take(NetworkMaxPostDataBytes.toInt())
            postDataTruncated = true
        }

        val request = JSONObject()
            .put("request_id", record.requestId)
            .put("url", record.url)
            .put("method", record.method)
            .put("resource_type", record.resourceType)
            .put("frame_id", record.frameId)
            .put("session_id", record.sessionId)
            .put("has_post_data", record.hasPostData)
            .put("redirect_count", record.redirectCount)
        if (includeRequestHeaders) {
            request.put(
                "headers",
                BrowserHeaderRedaction.toJson(record.requestHeaders, includeSensitive),
            )
        }
        if (includePostData) {
            request.put("post_data", postData)
            request.put("post_data_truncated", postDataTruncated)
        }

        val response = if (record.status != null || record.responseHeaders.isNotEmpty()) {
            JSONObject()
                .put("status", record.status ?: JSONObject.NULL)
                .put("status_text", record.statusText)
                .put("mime_type", record.mimeType)
                .put("remote_ip", record.remoteIPAddress)
                .put("protocol", record.protocolName)
                .put("from_disk_cache", record.fromDiskCache)
                .put("from_service_worker", record.fromServiceWorker)
                .put("encoded_data_length", record.encodedDataLength ?: JSONObject.NULL)
                .also { payload ->
                    if (includeResponseHeaders) {
                        payload.put(
                            "headers",
                            BrowserHeaderRedaction.toJson(
                                record.responseHeaders,
                                includeSensitive,
                            ),
                        )
                    }
                }
        } else {
            null
        }

        val durationMillis = record.durationMillis ?: 0L
        val timing = JSONObject()
            .put("started_at_ms", record.startedAtMillis)
            .put(
                "finished_at_ms",
                record.durationMillis?.let { record.startedAtMillis + it } ?: JSONObject.NULL,
            )
            .put("duration_ms", durationMillis)
        val body = if (includeResponseBody && session != null) {
            fetchResponseBody(
                session = session,
                record = record,
                encoding = bodyMode,
                inline = inline,
                inlineMaxBytes = inlineMaxBytes,
                maxBytes = maxBytes,
            )
        } else {
            null
        }

        return BrowserDataPayload.render(
            JSONObject().apply {
                put("ok", true)
                put("request", request)
                if (response != null) put("response", response)
                put("timing", timing)
                if (body != null) put("body", body)
                put("failed", record.failed)
                put("error_text", record.errorText)
                put(
                    "summary",
                    "${record.method} ${record.url} " +
                        "${record.status?.toString() ?: "pending"} ($durationMillis ms)",
                )
            },
        )
    }

    private fun normalizeEncoding(rawValue: String): String {
        val value = rawValue.trim().lowercase()
        return if (value == "text" || value == "base64") value else "auto"
    }

    private fun mimeTypeFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun safeArtifactFileName(url: String, mimeType: String): String {
        val rawName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val sanitized = rawName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
            .ifBlank { "resource" }
        if (sanitized.substringAfterLast('.', "").isNotBlank()) return sanitized
        return (sanitized + extensionForMime(mimeType)).take(60)
    }

    private fun extensionForMime(mimeType: String): String {
        val normalized = mimeType.substringBefore(';').trim().lowercase()
        return when {
            normalized.contains("html") -> ".html"
            normalized.contains("css") -> ".css"
            normalized.contains("javascript") || normalized.contains("ecmascript") -> ".js"
            normalized.contains("json") -> ".json"
            normalized == "image/png" -> ".png"
            normalized == "image/jpeg" -> ".jpg"
            normalized == "image/gif" -> ".gif"
            normalized == "image/webp" -> ".webp"
            normalized.contains("svg") -> ".svg"
            normalized.contains("woff2") -> ".woff2"
            normalized.contains("pdf") -> ".pdf"
            else -> ".bin"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xff
            hex[index * 2] = HexDigits[value ushr 4]
            hex[index * 2 + 1] = HexDigits[value and 0x0f]
        }
        return String(hex)
    }

    private fun specificCdpErrorCode(message: String): String = when {
        message.contains("No resource with given identifier found", ignoreCase = true) ->
            "NOT_OBSERVED"
        message.contains("No data found for resource", ignoreCase = true) -> "BODY_EVICTED"
        message.contains("No resource with given URL", ignoreCase = true) ->
            "RESOURCE_NOT_IN_TREE"
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) -> "TIMEOUT"
        else -> ""
    }

    private fun cdpErrorCode(message: String): String =
        specificCdpErrorCode(message).ifBlank { "CDP_ERROR" }

    private fun isTextMimeType(mimeType: String): Boolean {
        val normalized = mimeType.substringBefore(';').trim().lowercase()
        return normalized.startsWith("text/") ||
            normalized.contains("json") ||
            normalized.contains("xml") ||
            normalized.contains("javascript") ||
            normalized.contains("ecmascript") ||
            normalized.contains("css") ||
            normalized.contains("svg") ||
            normalized.contains("x-www-form-urlencoded")
    }

    private fun errorCodeFor(throwable: Throwable): String =
        if (throwable is TimeoutCancellationException) {
            "TIMEOUT"
        } else {
            cdpErrorCode(throwable.message.orEmpty())
        }

    private fun resourceError(
        code: String,
        errmsg: String,
        hint: String = hintForCode(code),
    ): String = resourceErrorObject(code, errmsg, hint).toString()

    private fun resourceErrorObject(
        code: String,
        errmsg: String,
        hint: String = hintForCode(code),
    ): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("errmsg", errmsg.ifBlank { code })
        .put("hint", hint)

    private fun hintForCode(code: String): String = when (code) {
        "MISSING_TARGET" -> "Pass either 'url' or 'request_id'."
        "NOT_OBSERVED" ->
            "Use list_requests to find a tracked request_id, or pass a url with allow_network=true."
        "RESOURCE_NOT_IN_TREE" ->
            "Use list_resources to verify the resource URL, or pass allow_network=true."
        "BODY_EVICTED" ->
            "The response body is no longer buffered; retry right after the request or pass allow_network=true."
        "NETWORK_FETCH_DISABLED" ->
            "Set allow_network=true to let Chrome fetch the resource again."
        "SAVE_FAILED" -> "The artifact could not be written to the Alpine workspace."
        "TIMEOUT" -> "The CDP request timed out; retry or reduce the requested size."
        "CDP_ERROR" ->
            "Chrome rejected the command; errmsg carries the raw CDP message."
        else -> ""
    }

    private suspend fun writeGuestArtifact(
        subdirectory: String,
        fileName: String,
        bytes: ByteArray,
    ): String? = runCatching {
        val directory = "$BrowserArtifactRoot/$subdirectory"
        alpineRuntime.ensureGuestDirectory(directory)
        val guestPath = "$directory/$fileName"
        alpineRuntime.resolveGuestPath(guestPath).writeBytes(bytes)
        guestPath
    }.getOrNull()

    private inner class CdpSession(
        private val url: String,
    ) {
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private val opened = CompletableDeferred<Unit>()
        // frame id -> owning CDP session, for every frame of every attached child target.
        private val frameSessions = ConcurrentHashMap<String, String>()
        // child session -> its root frame, so the resource tree is queried once per session.
        private val childSessionRoots = ConcurrentHashMap<String, String>()
        @Volatile
        private var socket: WebSocket? = null
        @Volatile
        var onEvent: ((method: String, params: JSONObject, sessionId: String) -> Unit)? = null
        @Volatile
        var isOpen: Boolean = false
            private set

        suspend fun connect() {
            socket = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        isOpen = true
                        if (!opened.isCompleted) opened.complete(Unit)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val id = payload.optInt("id", 0)
                        if (id != 0) {
                            val deferred = pending.remove(id) ?: return
                            val error = payload.optJSONObject("error")
                            if (error != null) {
                                deferred.completeExceptionally(
                                    IllegalStateException(
                                        error.optString("message")
                                            .ifBlank { "Chrome CDP command failed." },
                                    ),
                                )
                            } else {
                                deferred.complete(payload)
                            }
                            return
                        }
                        val method = payload.optString("method")
                        if (method.isEmpty()) return
                        val params = payload.optJSONObject("params") ?: JSONObject()
                        val sessionId = payload.optString("sessionId")
                        runCatching { onEvent?.invoke(method, params, sessionId) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        fail(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        fail(IllegalStateException(reason.ifBlank { "Chrome CDP closed." }))
                    }
                },
            )
            withTimeout(ChromeCommandTimeoutMillis) { opened.await() }
        }

        suspend fun send(
            method: String,
            params: JSONObject,
            sessionId: String = "",
            timeoutMillis: Long = ChromeCommandTimeoutMillis,
        ): JSONObject {
            check(isOpen) { "Chrome CDP is not connected." }
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<JSONObject>()
            pending[id] = deferred
            try {
                val message = JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
                if (sessionId.isNotBlank()) message.put("sessionId", sessionId)
                val sent = socket?.send(message.toString()) == true
                if (!sent) {
                    error("Unable to send Chrome CDP command.")
                }
                return withTimeout(timeoutMillis) { deferred.await() }
                    .optJSONObject("result")
                    ?: JSONObject()
            } finally {
                pending.remove(id)
            }
        }

        suspend fun trySend(
            method: String,
            params: JSONObject,
            sessionId: String = "",
            timeoutMillis: Long = ChromeCommandTimeoutMillis,
        ): Result<JSONObject> = runCatching {
            send(method, params, sessionId, timeoutMillis)
        }

        suspend fun mainFrameId(): String = runCatching {
            send("Page.getFrameTree", JSONObject())
                .optJSONObject("frameTree")
                ?.optJSONObject("frame")
                ?.optString("id")
                .orEmpty()
        }.getOrDefault("")

        fun rememberFrameSession(frameId: String, sessionId: String) {
            frameSessions[frameId] = sessionId
        }

        fun rememberChildSessionRoot(sessionId: String, rootFrameId: String) {
            childSessionRoots[sessionId] = rootFrameId
        }

        fun forgetChildSession(sessionId: String) {
            childSessionRoots.remove(sessionId)
            frameSessions.entries.removeAll { it.value == sessionId }
        }

        fun childSessions(): Set<String> = childSessionRoots.keys.toSet()

        fun sessionForFrame(frameId: String): String = frameSessions[frameId].orEmpty()

        fun sendBlocking(method: String, params: JSONObject) {
            val id = nextId.getAndIncrement()
            socket?.send(
                JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
                    .toString()
            )
        }

        fun close() {
            isOpen = false
            socket?.close(1000, "closing")
            socket = null
            frameSessions.clear()
            childSessionRoots.clear()
            fail(IllegalStateException("Chrome CDP session closed."))
        }

        private fun fail(throwable: Throwable) {
            isOpen = false
            if (!opened.isCompleted) opened.completeExceptionally(throwable)
            pending.values.forEach { deferred ->
                if (!deferred.isCompleted) deferred.completeExceptionally(throwable)
            }
            pending.clear()
        }
    }

    private data class ChromeKey(
        val key: String,
        val code: String,
        val virtualKeyCode: Int,
    ) {
        companion object {
            fun from(rawValue: String): ChromeKey = when (rawValue.trim().uppercase()) {
                "ENTER", "RETURN" -> ChromeKey("Enter", "Enter", 13)
                "TAB" -> ChromeKey("Tab", "Tab", 9)
                "BACKSPACE", "BACK" -> ChromeKey("Backspace", "Backspace", 8)
                "DELETE", "DEL" -> ChromeKey("Delete", "Delete", 46)
                "ESC", "ESCAPE" -> ChromeKey("Escape", "Escape", 27)
                "SPACE" -> ChromeKey(" ", "Space", 32)
                "ARROWUP", "UP" -> ChromeKey("ArrowUp", "ArrowUp", 38)
                "ARROWDOWN", "DOWN" -> ChromeKey("ArrowDown", "ArrowDown", 40)
                "ARROWLEFT", "LEFT" -> ChromeKey("ArrowLeft", "ArrowLeft", 37)
                "ARROWRIGHT", "RIGHT" -> ChromeKey("ArrowRight", "ArrowRight", 39)
                "HOME" -> ChromeKey("Home", "Home", 36)
                "END" -> ChromeKey("End", "End", 35)
                "PAGEUP" -> ChromeKey("PageUp", "PageUp", 33)
                "PAGEDOWN" -> ChromeKey("PageDown", "PageDown", 34)
                else -> {
                    val value = rawValue.take(1).ifBlank { " " }
                    ChromeKey(value, "Key${value.uppercase()}", value.uppercase().first().code)
                }
            }
        }
    }
}

private data class BrowserResourceItem(
    val url: String,
    val type: String,
    val mimeType: String,
    val contentSize: Long,
    val failed: Boolean,
    val canceled: Boolean,
    val frameId: String,
    val requestId: String,
    val status: Int?,
    val source: String,
)
