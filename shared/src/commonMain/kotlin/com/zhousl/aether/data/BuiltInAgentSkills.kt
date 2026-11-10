package com.zhousl.aether.data

const val CreateExtensionSkillId = "create-extension"

data class BuiltInAgentSkill(
    val id: String,
    val actionLabel: String,
    val markdown: String,
)

val BuiltInAgentSkills: List<BuiltInAgentSkill> = listOf(
    BuiltInAgentSkill(
        id = CreateExtensionSkillId,
        actionLabel = "Create Extensions",
        markdown = """
            ---
            name: Create Extension
            description: Create, install, and verify an Aether Extension when the user asks to customize Aether's interface, behavior, tools, commands, hooks, settings, or Agent workflow.
            compatibility: Aether 2.x
            metadata:
              short-description: Build and load an Aether Extension
            ---

            # Create an Aether Extension

            Turn the requested Aether customization into a working Extension, install it, and verify that it loaded. Complete the workflow without asking the user to import files or operate the Extensions settings screen.

            ## Choose the extension surface

            - Use the Aether Script Extension API for native mobile UI, settings pages, composer widgets, message renderers, state, storage, actions, events, and interceptors. Script Extensions reload without restarting the app.
            - Add a standard Pi extension entry when the customization needs Agent tools, commands, hooks, or other Pi Coding Agent behavior. One package may expose both Pi and Aether entries from the same TypeScript file.
            - Use an Android Native Mod only when the user explicitly needs Android-only APIs that the Script API cannot provide. Native Mods are Android-only, trusted code, and require an app restart, so they do not satisfy a cross-platform or uninterrupted customization request.

            ## Read the authoritative references

            Read the sections relevant to the requested feature before implementing it:

            - Documentation site: https://aether.baimoqilin.com/docs/extensions/overview.md
            - Complete Markdown reference: https://github.com/Zhou-Shilin/Aether/blob/main/docs/AETHER_EXTENSIONS.md
            - Extension API declarations and examples: https://github.com/Zhou-Shilin/Aether/tree/main/packages/extension-api
            - Combined Pi and Aether example: https://github.com/Zhou-Shilin/Aether/tree/main/examples/aether-extension

            When the current workspace is an Aether source checkout, prefer its local `docs/AETHER_EXTENSIONS.md`, `packages/extension-api/src/index.ts`, and `examples/aether-extension/` files because they match the running source version.

            ## Build

            1. Inspect the current workspace and the user's request. Reuse an existing Extension package when one clearly owns the customization; otherwise create a focused package under `.aether/extensions-src/<slug>/` in the workspace.
            2. Create a valid `package.json` with a stable package name, version, `"type": "module"`, and the required manifests. Declare Aether Script entrypoints under `aether.extensions`, Pi entrypoints under `pi.extensions`, or both. Target Aether Script API version 2.
            3. Implement the smallest complete Extension that satisfies the request. Use `@baimoqilin/aether-extension-api` for Aether types and helpers; Aether supplies that module to the runtime. Follow the documented slot names, component targets, service contracts, and lifecycle cleanup behavior exactly.
            4. Check JSON syntax and run any available TypeScript or package tests. Keep runtime dependencies in `dependencies`; declaration-only and build dependencies belong in `devDependencies`.

            A minimal Script manifest has this shape:

            ```json
            {
              "name": "aether-my-extension",
              "version": "1.0.0",
              "type": "module",
              "aether": {
                "api": { "min": 2, "max": 2 },
                "extensions": ["./index.ts"]
              },
              "devDependencies": {
                "@baimoqilin/aether-extension-api": "^2.0.0"
              }
            }
            ```

            ## Install and load

            Use `aether_extension_manage`; do not leave installation to the user.

            1. Build an absolute local package source using the manifest name and directory: `npm:<package-name>@file:<absolute-package-directory>`.
            2. Call `aether_extension_manage` with `action: "install_package"` and that `source`. If the same source is already installed, use `action: "update_package"` after edits.
            3. Inspect the returned package and reload data. Then call `action: "list"` and verify that the package and expected Aether/Pi entrypoints are present and that no load error was returned.
            4. If loading fails, fix the source, update the package, and verify again. Use `action: "remove_package"` only to roll back a package that cannot be made valid or when the user asks to uninstall it.

            Script files are watched after a successful load, but local `file:` packages are installed into Aether's managed package directory. After changing the source package, call `update_package` so the installed copy is refreshed.
        """.trimIndent() + "\n",
    ),
)

val BuiltInAgentSkillIds: List<String> = BuiltInAgentSkills.map(BuiltInAgentSkill::id)
