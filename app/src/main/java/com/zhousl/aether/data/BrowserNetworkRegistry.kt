package com.zhousl.aether.data

import java.util.LinkedHashMap
import org.json.JSONObject

internal data class BrowserRequestRecord(
    val seq: Long,
    val requestId: String,
    val sessionId: String,
    val frameId: String,
    val loaderId: String,
    var url: String,
    var method: String,
    val resourceType: String,
    var requestHeaders: Map<String, String>,
    var postData: String,
    val hasPostData: Boolean,
    val startedAtMillis: Long,
    /** Monotonic `MonotonicTime` seconds from the CDP event; the only clock that can be
     *  subtracted from `loadingFinished.timestamp`. */
    val startedAtSeconds: Double,
    val wallTimeSeconds: Double,
    var redirectCount: Int = 0,
    var status: Int? = null,
    var statusText: String = "",
    var mimeType: String = "",
    var responseHeaders: Map<String, String> = emptyMap(),
    var remoteIPAddress: String = "",
    var protocolName: String = "",
    var fromDiskCache: Boolean = false,
    var fromServiceWorker: Boolean = false,
    var encodedDataLength: Long? = null,
    var finishedAtSeconds: Double? = null,
    var durationMillis: Long? = null,
    var failed: Boolean = false,
    var errorText: String = "",
)

internal data class BrowserRequestFilter(
    val urlContains: String = "",
    val method: String = "",
    val status: Int? = null,
    val resourceType: String = "",
    val mimePrefix: String = "",
)

internal class BrowserNetworkRegistry(
    private val maxRecords: Int = 2_000,
    private val maxHeaderValueChars: Int = 8_192,
    private val maxPostDataChars: Int = 65_536,
) {
    private val lock = Any()
    // accessOrder = true is what makes eviction LRU instead of FIFO.
    private val records = LinkedHashMap<String, BrowserRequestRecord>(16, 0.75f, true)
    private var nextSeq = 1L

    val size: Int
        get() = synchronized(lock) { records.size }

    fun onRequestWillBeSent(params: JSONObject, sessionId: String) {
        val requestId = params.optString("requestId")
        if (requestId.isBlank()) return
        val request = params.optJSONObject("request") ?: JSONObject()
        val redirectResponse = params.optJSONObject("redirectResponse")
        synchronized(lock) {
            val existing = records[requestId]
            if (redirectResponse != null && existing != null) {
                existing.url = request.optString("url", existing.url)
                existing.method = request.optString("method", existing.method)
                existing.requestHeaders = toHeaders(request.optJSONObject("headers"))
                existing.postData = limitedText(request.optString("postData"), maxPostDataChars)
                existing.status = redirectResponse.optInt("status", existing.status ?: 0)
                existing.statusText = redirectResponse.optString("statusText")
                existing.responseHeaders = toHeaders(redirectResponse.optJSONObject("headers"))
                existing.mimeType = redirectResponse.optString("mimeType")
                existing.redirectCount += 1
                return
            }
            val wallTime = params.optDouble("wallTime", Double.NaN)
            records[requestId] = BrowserRequestRecord(
                seq = nextSeq++,
                requestId = requestId,
                sessionId = sessionId,
                frameId = params.optString("frameId"),
                loaderId = params.optString("loaderId"),
                url = request.optString("url"),
                method = request.optString("method"),
                resourceType = params.optString("type"),
                requestHeaders = toHeaders(request.optJSONObject("headers")),
                postData = limitedText(request.optString("postData"), maxPostDataChars),
                hasPostData = request.optBoolean("hasPostData", false),
                startedAtMillis = if (wallTime.isNaN()) {
                    System.currentTimeMillis()
                } else {
                    (wallTime * 1000.0).toLong()
                },
                wallTimeSeconds = if (wallTime.isNaN()) 0.0 else wallTime,
                startedAtSeconds = params.optDouble("timestamp", Double.NaN),
            )
            while (records.size > maxRecords) {
                val oldestKey = records.keys.firstOrNull() ?: break
                records.remove(oldestKey)
            }
        }
    }

    fun onResponseReceived(params: JSONObject, sessionId: String) {
        val requestId = params.optString("requestId")
        val response = params.optJSONObject("response") ?: return
        synchronized(lock) {
            val record = records[requestId] ?: return
            record.status = response.optInt("status", record.status ?: 0)
            record.statusText = response.optString("statusText", record.statusText)
            record.responseHeaders = toHeaders(response.optJSONObject("headers"))
            record.mimeType = response.optString("mimeType", record.mimeType)
            record.remoteIPAddress = response.optString("remoteIPAddress", record.remoteIPAddress)
            record.protocolName = response.optString("protocol", record.protocolName)
            record.fromDiskCache = response.optBoolean("fromDiskCache", record.fromDiskCache)
            record.fromServiceWorker = response.optBoolean("fromServiceWorker", record.fromServiceWorker)
            if (response.has("encodedDataLength")) {
                record.encodedDataLength =
                    response.optDouble("encodedDataLength", 0.0).toLong()
            }
        }
    }

    fun onLoadingFinished(params: JSONObject, sessionId: String) {
        val requestId = params.optString("requestId")
        synchronized(lock) {
            val record = records[requestId] ?: return
            if (params.has("encodedDataLength")) {
                record.encodedDataLength = params.optDouble("encodedDataLength", 0.0).toLong()
            }
            finishRecord(record, params)
        }
    }

    fun onLoadingFailed(params: JSONObject, sessionId: String) {
        val requestId = params.optString("requestId")
        synchronized(lock) {
            val record = records[requestId] ?: return
            record.failed = true
            record.errorText = params.optString("errorText")
            finishRecord(record, params)
        }
    }

    fun onRequestServedFromCache(params: JSONObject, sessionId: String) {
        val requestId = params.optString("requestId")
        synchronized(lock) {
            records[requestId]?.fromDiskCache = true
        }
    }

    fun findById(requestId: String): BrowserRequestRecord? = synchronized(lock) {
        records[requestId]
    }

    fun findLatestByUrl(url: String): BrowserRequestRecord? = synchronized(lock) {
        val match = records.values
            .asSequence()
            .filter { it.url == url }
            .maxByOrNull { it.seq } ?: return@synchronized null
        // Read back through the access-ordered map so a hot URL is not evicted first.
        records[match.requestId]
    }

    /** One pass over all records for callers that resolve many URLs at once. */
    fun latestByUrl(urls: Collection<String>): Map<String, BrowserRequestRecord> = synchronized(lock) {
        if (urls.isEmpty()) return@synchronized emptyMap()
        val wanted = urls.toHashSet()
        val result = HashMap<String, BrowserRequestRecord>(wanted.size)
        records.values.forEach { record ->
            val url = record.url
            if (url in wanted) {
                val existing = result[url]
                if (existing == null || existing.seq < record.seq) result[url] = record
            }
        }
        result
    }

    fun list(
        sinceSeq: Long,
        filter: BrowserRequestFilter,
        limit: Int,
    ): List<BrowserRequestRecord> = synchronized(lock) {
        records.values
            .asSequence()
            .filter { it.seq > sinceSeq }
            .filter { record -> record.matches(filter) }
            .sortedBy { it.seq }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun snapshot(): List<BrowserRequestRecord> = synchronized(lock) {
        records.values.toList()
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
            nextSeq = 1L
        }
    }

    private fun toHeaders(headers: JSONObject?): Map<String, String> {
        if (headers == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = limitedText(headers.opt(key)?.toString().orEmpty(), maxHeaderValueChars)
        }
        return result
    }

    private fun finishRecord(record: BrowserRequestRecord, params: JSONObject) {
        val finishedSeconds = params.optDouble("timestamp", Double.NaN)
        if (finishedSeconds.isNaN()) return
        record.finishedAtSeconds = finishedSeconds
        val startedSeconds = record.startedAtSeconds
        if (!startedSeconds.isNaN()) {
            record.durationMillis =
                ((finishedSeconds - startedSeconds) * 1000.0).toLong().coerceAtLeast(0L)
        }
    }

    private fun limitedText(value: String, limit: Int): String =
        if (limit >= 0 && value.length > limit) value.substring(0, limit) else value
}

internal object BrowserHeaderRedaction {
    private val sensitiveHeaders = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "set-cookie2",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token",
    )

    fun toJson(headers: Map<String, String>, includeSensitive: Boolean): JSONObject {
        val result = JSONObject()
        headers.forEach { (key, value) ->
            val redacted = !includeSensitive && key.lowercase() in sensitiveHeaders
            result.put(key, if (redacted) "<redacted>" else value)
        }
        return result
    }
}

private fun BrowserRequestRecord.matches(filter: BrowserRequestFilter): Boolean {
    if (filter.urlContains.isNotBlank() && !url.contains(filter.urlContains, ignoreCase = true)) {
        return false
    }
    if (filter.method.isNotBlank() && !method.equals(filter.method, ignoreCase = true)) {
        return false
    }
    if (filter.status != null && status != filter.status) return false
    if (
        filter.resourceType.isNotBlank() &&
        !resourceType.equals(filter.resourceType, ignoreCase = true)
    ) {
        return false
    }
    if (filter.mimePrefix.isNotBlank() && !mimeType.startsWith(filter.mimePrefix, ignoreCase = true)) {
        return false
    }
    return true
}