package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DynamicPromptPlaceholderRegex = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

internal fun buildPiAgentInstructions(
    settings: AppSettings,
    workspaceDirectory: String,
    runtimeId: LocalRuntimeId,
    agentModeEnabled: Boolean,
    chromeEnabled: Boolean = false,
): String = buildString {
    val configuredPrompt = expandDynamicPromptPlaceholders(settings.systemPrompt).trim()
    if (configuredPrompt.isNotBlank()) {
        append(configuredPrompt)
        append("\n\n")
    }
    append(
        "You are running inside Aether on Android. " +
            "The current local runtime is ${runtimeId.storageValue} and its session cwd is $workspaceDirectory. " +
            "Aether keeps Alpine and Termux workspaces independent when the runtime changes. " +
            "User-uploaded files are placed under uploads/; use read on the provided path when image or file contents are needed. " +
            "Aether-owned configuration, Skill, runtime, Extension, Phone Use, scheduled-task, and developer operations are exposed only through available aether_* tools. " +
            "Never modify LLM provider credentials or model configuration through self-management tools. " +
            "Only claim device actions or command results that were actually observed."
    )
    if (agentModeEnabled) {
        append(
            "\n\nPhone Use is enabled for this chat. Use agent_display to operate Android via Accessibility. " +
                "The current mode is ${if (!settings.phoneUseVirtualDisplayEnabled) "foreground" else "Virtual Display (background)"}. " +
                "Input strategy: ${settings.phoneUseInputStrategy.storageValue}; output strategy: ${settings.phoneUseOutputStrategy.storageValue}. " +
                "observe/start and action results follow the input strategy. Element observations return a tree with temporary node_id values; screenshot observations return an image. " +
                "Prefer the configured output strategy. ${if (settings.phoneUseAllowStrategySwitch) "You may manually use the alternative when needed: tree/screenshot or input_strategy overrides observation for one action; node_id selects element actions, coordinates select gestures." else "Alternative strategy switching is disabled. Use only the configured observation and operation strategy."} " +
                "Node IDs are scoped to the latest observation, and the host revalidates the specific element before acting. Dynamic loading elsewhere does not invalidate an unchanged target. " +
                "Use visible, enabled, actionable elements. On element errors, action_succeeded=false: inspect the returned observation and choose a valid target. Avoid repeated observe/click loops; use an allowed real screenshot/gesture tool call if the target cannot be identified. " +
                "Never simulate USER/tool_result messages or write fictional tool outputs. Only actual agent_display results establish whether a device action succeeded. " +
                "Coordinate tap/swipe uses normalized 0..1000 coordinates: pixel_x / image_width * 1000, pixel_y / image_height * 1000. " +
                "Use text with an editable node_id; it replaces the field contents. Password values are redacted. " +
                "Foreground Phone Use changes the screen the user sees. Virtual Display global navigation is unavailable; use app elements. " +
                "When foreground phone work is complete, you may call agent_display with action=stop and return_to_aether=true to bring the user back to Aether for your final reply. " +
                "Leave return_to_aether omitted/false when the user should remain in the operated app to see or use the result. This choice is unavailable in Virtual Display mode."

        )
    }
    if (chromeEnabled) {
        append(
            "\n\nThe chat has enabled the browser tool (Chrome Extension tool). Prefer selectors and DOM-reading actions, and use coordinates only as a fallback." +
                "\n\nTo inspect loaded page resources or captured network traffic, use the browser tool's list_resources, get_resource, list_requests, and get_request actions instead of re-fetching URLs."
        )
    }
}

private fun expandDynamicPromptPlaceholders(
    prompt: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): String {
    if (!prompt.contains("{{")) return prompt
    val values = mapOf(
        "current_datetime" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "current_date" to now.toLocalDate().toString(),
        "current_time" to now.toLocalTime().withNano(0).toString(),
        "timezone" to now.zone.id,
        "unix_timestamp" to now.toEpochSecond().toString(),
    )
    return DynamicPromptPlaceholderRegex.replace(prompt) { match ->
        values[match.groupValues[1].lowercase(Locale.US)] ?: match.value
    }
}
