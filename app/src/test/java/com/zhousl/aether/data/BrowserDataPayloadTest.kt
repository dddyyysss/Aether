package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDataPayloadTest {
    @Test
    fun renderKeepsExistingFields() {
        val rendered = BrowserDataPayload.render(
            JSONObject()
                .put("ok", true)
                .put("count", 3),
        )

        val payload = JSONObject(rendered)
        assertTrue(payload.getBoolean("ok"))
        assertEquals(3, payload.getInt("count"))
    }

    @Test
    fun renderMovesWholePayloadIntoStdout() {
        val rendered = BrowserDataPayload.render(
            JSONObject()
                .put("ok", true)
                .put("count", 2),
        )

        val stdout = JSONObject(rendered).getString("stdout")
        val inner = JSONObject(stdout)
        assertEquals(2, inner.getInt("count"))
        assertFalse(inner.has("stdout"))
    }

    @Test
    fun renderDoesNotOverwriteExistingStdout() {
        val rendered = BrowserDataPayload.render(
            JSONObject()
                .put("ok", true)
                .put("stdout", "custom"),
        )

        assertEquals("custom", JSONObject(rendered).getString("stdout"))
    }

    @Test
    fun renderIsIdempotent() {
        val payload = JSONObject()
            .put("ok", true)
            .put("count", 1)

        val first = JSONObject(BrowserDataPayload.render(payload)).getString("stdout")
        val second = JSONObject(BrowserDataPayload.render(payload)).getString("stdout")

        assertEquals(first, second)
        assertFalse(JSONObject(second).has("stdout"))
    }
}