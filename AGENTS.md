本项目是 Android-only 的 Aether fork（源自 Zhou-Shilin/Aether）。所有改动只需在 Android 生效；`shared/` 里的公共代码目前也只被 Android `app` 消费。

复盘记录：

- 修改 `shared/src/commonMain` 后，必须在 Android `app` 中找到实际入口并编译、安装验证，不能仅凭共享层代码推断改动已生效。
- Compose 中使用 `Box` 叠加阴影时，`matchParentSize()` 子项不参与父容器尺寸测量。父容器必须至少有一个正常参与测量的内容子项（例如固定高度的 `Row`），否则胶囊、按钮等控件可能宽度变为 0 而完全消失。
- 安装完成后，必须用实际连接设备核对包名与安装结果，不能只看构建成功。
- 搜索文件或代码时，默认始终排除缓存目录、第三方源码目录、构建目录及其他生成文件目录，避免被无用信息淹没；除非明确知道自己需要搜索这些目录。
- 浏览器工具的四个数据类动作（`list_resources` / `get_resource` / `list_requests` / `get_request`）把 payload JSON 放在 `stdout` 里，因为桥接层只把 `stdout` 转发给模型；不要改成只写人类摘要，否则数据到不了模型。

文档约定：

- 本地材料不入库：设计稿、调研笔记、差距分析这类文档只放本地，用 `*.local.md` 或 `local-docs/`（用 `.gitignore` 忽略这两类），不要提交。入库文档只写与代码同步的稳定契约。
- 文档与代码同步：修改行为时在同一提交内更新对应文档；无法确认的陈述宁可删掉，不要保留猜测。

真机安装流程：

- Android：先用 `adb devices -l` 确认目标 serial，再执行 `./gradlew :app:assembleDebug --no-daemon` 和 `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` 直接覆盖安装正式 APK。默认禁止构建或安装 `app-debug-androidTest.apk` 等测试包；以后不需要安装测试包，除非用户明确要求。安装后必须执行 `adb -s <serial> shell pm path com.baimoqilin.aether`，并用 `dumpsys package` 核对版本信息。多设备环境禁止使用不带 `-s` 的 `adb install`。
