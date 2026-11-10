package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNetworkRegistryTest {
    @Test
    fun mergesRequestResponseAndCompletion() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "1000.2", type = "Fetch"),
            "",
        )
        registry.onResponseReceived(
            responseReceived(
                requestId = "1000.2",
                status = 200,
                statusText = "OK",
                mimeType = "application/json",
            ),
            "",
        )
        registry.onLoadingFinished(
            JSONObject()
                .put("requestId", "1000.2")
                .put("timestamp", 1.5)
                .put("encodedDataLength", 456),
            "",
        )

        val record = registry.findById("1000.2")
        assertNotNull(record)
        assertEquals(200, record!!.status)
        assertEquals("OK", record.statusText)
        assertEquals("application/json", record.mimeType)
        assertEquals(456L, record.encodedDataLength)
        assertEquals(500L, record.durationMillis)
        assertFalse(record.failed)
        assertEquals("GET", record.method)
        assertEquals("Fetch", record.resourceType)
    }

    @Test
    fun redirectUpdatesExistingRecord() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "redirect-1",
                url = "https://example.com/old",
                method = "POST",
            ),
            "",
        )
        val redirectResponse = JSONObject()
            .put("status", 302)
            .put("statusText", "Found")
            .put("mimeType", "text/html")
            .put("headers", headersJson(mapOf("location" to "https://example.com/new")))
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "redirect-1",
                url = "https://example.com/new",
                method = "GET",
                redirectResponse = redirectResponse,
            ),
            "",
        )

        assertEquals(1, registry.size)
        val record = registry.findById("redirect-1")!!
        assertEquals(1, record.redirectCount)
        assertEquals("https://example.com/new", record.url)
        assertEquals("GET", record.method)
        assertEquals(302, record.status)
        assertEquals("Found", record.statusText)
        assertEquals("text/html", record.mimeType)
        assertEquals(
            "https://example.com/new",
            record.responseHeaders["location"],
        )
    }

    @Test
    fun loadingFailedMarksRecordFailed() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(requestWillBeSent(requestId = "failed-1"), "")
        registry.onLoadingFailed(
            JSONObject()
                .put("requestId", "failed-1")
                .put("errorText", "net::ERR_FAILED")
                .put("timestamp", 2.0),
            "",
        )

        val record = registry.findById("failed-1")!!
        assertTrue(record.failed)
        assertEquals("net::ERR_FAILED", record.errorText)
        assertEquals(1_000L, record.durationMillis)
    }

    @Test
    fun evictsOldestRecordsWhenOverCapacity() {
        val registry = BrowserNetworkRegistry(maxRecords = 3)
        (1..5).forEach { index ->
            registry.onRequestWillBeSent(requestWillBeSent(requestId = "r$index"), "")
        }

        assertEquals(3, registry.size)
        assertNull(registry.findById("r1"))
        assertNull(registry.findById("r2"))
        assertNotNull(registry.findById("r3"))
        assertNotNull(registry.findById("r4"))
        assertNotNull(registry.findById("r5"))
    }

    @Test
    fun durationUsesTheMonotonicClockNotWallTime() {
        val registry = BrowserNetworkRegistry()
        // wallTime is epoch seconds, timestamp is MonotonicTime seconds. Mixing them
        // produced a negative delta that coerceAtLeast(0) flattened to zero.
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "clock-1", timestamp = 100.0, wallTime = 1_730_000_000.0),
            "",
        )
        registry.onLoadingFinished(
            JSONObject().put("requestId", "clock-1").put("timestamp", 100.25).put("encodedDataLength", 10),
            "",
        )

        val record = registry.findById("clock-1")!!
        assertEquals(250L, record.durationMillis)
        assertEquals(1_730_000_000_000L, record.startedAtMillis)
    }

    @Test
    fun evictionFollowsAccessOrderNotInsertionOrder() {
        val registry = BrowserNetworkRegistry(maxRecords = 3)
        listOf("a", "b", "c").forEach { id ->
            registry.onRequestWillBeSent(requestWillBeSent(requestId = id), "")
        }
        // Touch the oldest entry, then overflow: the untouched one goes first.
        assertNotNull(registry.findById("a"))
        registry.onRequestWillBeSent(requestWillBeSent(requestId = "d"), "")

        assertEquals(3, registry.size)
        assertNotNull(registry.findById("a"))
        assertNull(registry.findById("b"))
        assertNotNull(registry.findById("c"))
        assertNotNull(registry.findById("d"))
    }

    @Test
    fun latestByUrlResolvesManyUrlsInOnePass() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "first", url = "https://example.com/a.js"),
            "",
        )
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "second", url = "https://example.com/a.js"),
            "",
        )
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "other", url = "https://example.com/b.css"),
            "",
        )

        val found = registry.latestByUrl(
            setOf("https://example.com/a.js", "https://example.com/missing.png"),
        )
        assertEquals("second", found["https://example.com/a.js"]?.requestId)
        assertNull(found["https://example.com/missing.png"])
        assertEquals(1, found.size)
    }

    @Test
    fun listAppliesSinceSeqLimitAndFilters() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "a",
                url = "https://example.com/a.js",
                method = "GET",
            ),
            "",
        )
        registry.onResponseReceived(responseReceived("a", status = 200), "")
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "b",
                url = "https://api.example.com/b",
                method = "POST",
                type = "Fetch",
            ),
            "",
        )
        registry.onResponseReceived(
            responseReceived("b", status = 404, mimeType = "application/json"),
            "",
        )
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "c",
                url = "https://example.com/c.css",
                type = "Stylesheet",
            ),
            "",
        )

        val all = registry.list(0L, BrowserRequestFilter(), 10)
        assertEquals(listOf("a", "b", "c"), all.map { it.requestId })

        val afterFirst = registry.list(1L, BrowserRequestFilter(), 10)
        assertEquals(listOf("b", "c"), afterFirst.map { it.requestId })

        val urlFiltered = registry.list(
            0L,
            BrowserRequestFilter(urlContains = "api."),
            10,
        )
        assertEquals(listOf("b"), urlFiltered.map { it.requestId })

        val methodFiltered = registry.list(
            0L,
            BrowserRequestFilter(method = "post"),
            10,
        )
        assertEquals(listOf("b"), methodFiltered.map { it.requestId })

        val statusFiltered = registry.list(
            0L,
            BrowserRequestFilter(status = 404),
            10,
        )
        assertEquals(listOf("b"), statusFiltered.map { it.requestId })

        val limited = registry.list(0L, BrowserRequestFilter(), 2)
        assertEquals(listOf("a", "b"), limited.map { it.requestId })
    }

    @Test
    fun findLatestByUrlReturnsHighestSeq() {
        val registry = BrowserNetworkRegistry()
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "first", url = "https://example.com/data.json"),
            "",
        )
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "second", url = "https://example.com/data.json"),
            "",
        )
        registry.onRequestWillBeSent(
            requestWillBeSent(requestId = "other", url = "https://example.com/other.json"),
            "",
        )

        assertEquals("second", registry.findLatestByUrl("https://example.com/data.json")?.requestId)
        assertNull(registry.findLatestByUrl("https://example.com/missing.json"))
    }

    @Test
    fun truncatesLongHeaderValues() {
        val registry = BrowserNetworkRegistry(maxHeaderValueChars = 5)
        registry.onRequestWillBeSent(
            requestWillBeSent(
                requestId = "headers-1",
                headers = mapOf("x-long" to "abcdefgh"),
            ),
            "",
        )

        assertEquals("abcde", registry.findById("headers-1")?.requestHeaders?.get("x-long"))
    }

    @Test
    fun redactsSensitiveHeadersByDefault() {
        val headers = mapOf(
            "cookie" to "session=secret",
            "Authorization" to "Bearer token",
            "accept" to "text/html",
        )

        val redacted = BrowserHeaderRedaction.toJson(headers, includeSensitive = false)
        assertEquals("<redacted>", redacted.getString("cookie"))
        assertEquals("<redacted>", redacted.getString("Authorization"))
        assertEquals("text/html", redacted.getString("accept"))

        val sensitive = BrowserHeaderRedaction.toJson(headers, includeSensitive = true)
        assertEquals("session=secret", sensitive.getString("cookie"))
        assertEquals("Bearer token", sensitive.getString("Authorization"))
    }

    private fun requestWillBeSent(
        requestId: String,
        url: String = "https://example.com/app.js",
        method: String = "GET",
        type: String = "Script",
        timestamp: Double = 1.0,
        wallTime: Double = 1_730_000_000.0,
        headers: Map<String, String> = emptyMap(),
        postData: String? = null,
        hasPostData: Boolean = false,
        redirectResponse: JSONObject? = null,
    ): JSONObject {
        val request = JSONObject()
            .put("url", url)
            .put("method", method)
            .put("headers", headersJson(headers))
            .put("hasPostData", hasPostData)
        if (postData != null) request.put("postData", postData)
        return JSONObject()
            .put("requestId", requestId)
            .put("loaderId", "loader-1")
            .put("frameId", "frame-1")
            .put("timestamp", timestamp)
            .put("wallTime", wallTime)
            .put("type", type)
            .put("request", request)
            .apply {
                if (redirectResponse != null) put("redirectResponse", redirectResponse)
            }
    }

    private fun responseReceived(
        requestId: String,
        status: Int = 200,
        statusText: String = "OK",
        mimeType: String = "application/javascript",
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put(
            "response",
            JSONObject()
                .put("status", status)
                .put("statusText", statusText)
                .put("mimeType", mimeType)
                .put("headers", headersJson(headers))
                .put("remoteIPAddress", "127.0.0.1")
                .put("protocol", "http/1.1"),
        )

    private fun headersJson(headers: Map<String, String>): JSONObject =
        JSONObject().apply { headers.forEach { (key, value) -> put(key, value) } }
}