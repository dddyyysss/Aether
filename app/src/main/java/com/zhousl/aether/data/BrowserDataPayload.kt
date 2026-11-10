package com.zhousl.aether.data

import org.json.JSONObject

/**
 * Renders a browser inspection payload for the tool channel.
 * `stdout` is the only field the agent bridge forwards to the model, so the full
 * payload JSON must travel there for data actions.
 */
internal object BrowserDataPayload {
    fun render(payload: JSONObject): String {
        if (!payload.has("stdout")) {
            payload.put("stdout", payload.toString())
        }
        return payload.toString()
    }
}