package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject

internal data class BrowserFrameResource(
    val url: String,
    val type: String,
    val mimeType: String,
    val contentSize: Long,
    val failed: Boolean,
    val canceled: Boolean,
    val lastModifiedMillis: Long?,
)

internal data class BrowserFrameEntry(
    val frameId: String,
    val parentFrameId: String,
    val loaderId: String,
    val url: String,
    val name: String,
    val securityOrigin: String,
    val mimeType: String,
    val isMainFrame: Boolean,
    val sessionId: String,
    val resources: List<BrowserFrameResource>,
)

internal object BrowserResourceTreeParser {
    /**
     * @param frameTree `Page.getResourceTree` result's frameTree object.
     * @param sessionId CDP session that owns the tree (empty for the main session).
     * @param rootIsMainFrame Whether the root frame should be marked as the main frame.
     */
    fun parse(
        frameTree: JSONObject,
        sessionId: String,
        rootIsMainFrame: Boolean,
    ): List<BrowserFrameEntry> {
        val entries = mutableListOf<BrowserFrameEntry>()
        parseFrameTree(frameTree, sessionId, rootIsMainFrame, entries)
        return entries
    }

    private fun parseFrameTree(
        tree: JSONObject,
        sessionId: String,
        isMainFrame: Boolean,
        entries: MutableList<BrowserFrameEntry>,
    ) {
        val frame = tree.optJSONObject("frame") ?: return
        entries += BrowserFrameEntry(
            frameId = frame.optString("id"),
            parentFrameId = frame.optString("parentId"),
            loaderId = frame.optString("loaderId"),
            url = frame.optString("url"),
            name = frame.optString("name"),
            securityOrigin = frame.optString("securityOrigin"),
            mimeType = frame.optString("mimeType"),
            isMainFrame = isMainFrame,
            sessionId = sessionId,
            resources = parseResources(tree.optJSONArray("resources")),
        )
        val childFrames = tree.optJSONArray("childFrames") ?: return
        for (index in 0 until childFrames.length()) {
            val child = childFrames.optJSONObject(index) ?: continue
            parseFrameTree(
                tree = child.optJSONObject("frameTree") ?: child,
                sessionId = sessionId,
                isMainFrame = false,
                entries = entries,
            )
        }
    }

    private fun parseResources(resources: JSONArray?): List<BrowserFrameResource> {
        if (resources == null) return emptyList()
        val parsed = mutableListOf<BrowserFrameResource>()
        for (index in 0 until resources.length()) {
            val resource = resources.optJSONObject(index) ?: continue
            parsed += BrowserFrameResource(
                url = resource.optString("url"),
                type = resource.optString("type"),
                mimeType = resource.optString("mimeType"),
                contentSize = resource.optDouble("contentSize", 0.0).toLong(),
                failed = resource.optBoolean("failed", false),
                canceled = resource.optBoolean("canceled", false),
                lastModifiedMillis = if (resource.has("lastModified")) {
                    (resource.optDouble("lastModified", 0.0) * 1000.0).toLong()
                } else {
                    null
                },
            )
        }
        return parsed
    }
}