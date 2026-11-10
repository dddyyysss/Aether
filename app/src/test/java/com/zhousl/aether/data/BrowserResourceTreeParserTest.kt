package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResourceTreeParserTest {
    @Test
    fun parsesNestedFramesAndResources() {
        val frameTree = JSONObject()
            .put("frame", frameJson("main-frame", "https://example.com/"))
            .put(
                "resources",
                JSONArray().put(resourceJson("https://example.com/app.js", "Script", 1_200L)),
            )
            .put(
                "childFrames",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "frame",
                            frameJson(
                                id = "child-frame",
                                url = "https://example.com/embed",
                                parentId = "main-frame",
                            ),
                        )
                        .put(
                            "resources",
                            JSONArray().put(
                                resourceJson("https://example.com/logo.png", "Image", 3_400L),
                            ),
                        ),
                ),
            )

        val entries = BrowserResourceTreeParser.parse(frameTree, "", rootIsMainFrame = true)

        assertEquals(2, entries.size)
        assertEquals("main-frame", entries[0].frameId)
        assertTrue(entries[0].isMainFrame)
        assertEquals(1, entries[0].resources.size)
        assertEquals(1_200L, entries[0].resources[0].contentSize)
        assertEquals("child-frame", entries[1].frameId)
        assertEquals("main-frame", entries[1].parentFrameId)
        assertFalse(entries[1].isMainFrame)
        assertEquals("https://example.com/embed", entries[1].url)
        assertEquals(1, entries[1].resources.size)
        assertEquals(3_400L, entries[1].resources[0].contentSize)
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val frameTree = JSONObject()
            .put(
                "frame",
                JSONObject()
                    .put("id", "frame-only")
                    .put("url", "https://example.com/"),
            )
            .put(
                "resources",
                JSONArray().put(
                    JSONObject()
                        .put("url", "https://example.com/font.woff2")
                        .put("type", "Font")
                        .put("contentSize", 42),
                ),
            )

        val entries = BrowserResourceTreeParser.parse(frameTree, "child-session", true)

        assertEquals(1, entries.size)
        assertEquals("", entries[0].parentFrameId)
        assertEquals("child-session", entries[0].sessionId)
        val resource = entries[0].resources.single()
        assertEquals("", resource.mimeType)
        assertEquals(42L, resource.contentSize)
        assertFalse(resource.failed)
        assertFalse(resource.canceled)
        assertNull(resource.lastModifiedMillis)
    }

    @Test
    fun rootFrameIsNotMainForChildTargets() {
        val frameTree = JSONObject()
            .put("frame", frameJson("oopif-root", "https://frame.example.com/"))

        val entries = BrowserResourceTreeParser.parse(
            frameTree,
            sessionId = "oopif-session",
            rootIsMainFrame = false,
        )

        assertEquals(1, entries.size)
        assertFalse(entries[0].isMainFrame)
        assertEquals("oopif-session", entries[0].sessionId)
    }

    private fun frameJson(
        id: String,
        url: String,
        parentId: String? = null,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("loaderId", "loader-$id")
        .put("url", url)
        .put("name", "")
        .put("securityOrigin", "https://example.com")
        .put("mimeType", "text/html")
        .apply { if (parentId != null) put("parentId", parentId) }

    private fun resourceJson(url: String, type: String, contentSize: Long): JSONObject =
        JSONObject()
            .put("url", url)
            .put("type", type)
            .put("mimeType", "application/octet-stream")
            .put("contentSize", contentSize)
}