# 明心 · AI 费曼佛法研习（Android MVP 源码）

这是一份可继续开发的 Kotlin + Jetpack Compose Android Studio 工程源码，核心流程是：用户讲解 → AI 学生三轮追问 → AI 助教评估 → 用户重新讲解 → 生成复盘笔记。

## 当前实现

- 用户自己输入 DeepSeek API Key，本机 Android Keystore + AES/GCM 加密保存
- 使用官方 `https://api.deepseek.com/chat/completions` 和 `deepseek-flash`
- 多个佛法主题 + 自定义主题
- 独立的“AI 学生”与“AI 助教”系统提示词
- 连续 3 轮问题、JSON 四维评估、第二次讲解对比
- 已完成课堂和当前课堂保存在设备本地（SharedPreferences JSON）
- 安卓系统语音识别 Intent、安卓原生 TextToSpeech（依设备支持情况）

## 打开工程

1. 用最新稳定版 Android Studio 打开 `MingXinFeynman/` 文件夹。
2. 安装 Android SDK Platform 36 和 JDK 17。
3. 本工程包含 Gradle 构建脚本和 wrapper 配置。若项目中没有 `gradle/wrapper/gradle-wrapper.jar`，请使用你本机的 Gradle 8.13 在项目根目录运行 `gradle wrapper --gradle-version 8.13` 生成 wrapper，然后同步工程。
4. 连接 Android 8.0（API 26）及以上的安卓设备，Run app。
5. 在 App「设置」页粘贴你自己的 DeepSeek API Key，然后开始研习。

**状态说明：** 由于本交付环境没有 Android SDK 和 Gradle，尚未进行实际编译、安装与设备测试。这是 MVP 工程源码，不是已经验证可安装的 APK。

## 接入注意

- 用户支付自己的 DeepSeek API 调用费用。API Key 不要写入源码或发布到 GitHub。
- 语音输入初版调用手机系统语音服务；不同安卓品牌/区域的识别可用性不一致。DeepSeek 2026-11-13 开放的专用 ASR/TTS 端点可在下一版加入。
- 经论评价以大模型生成内容为主，并**未**内置经文库或检索校验。禁止把生成内容当作可靠经典引文。生产版建议接入已核对的经文底本与注疏资料，并提供引用位置。
- API 客户端当前为非流式请求，MVP 尚未实现重试退避、生产级网络诊断和计费日志。
- 历史保存在 SharedPreferences，本地应用卸载时删除；正式版可迁移 Room 并增加导出。
- 本工程按最新文档设置了 Compose BOM 2026.09.00、Activity Compose 1.13.0、AGP 8.13.2、Kotlin 2.3.21，若当地 Android Studio 依赖同步失败需按安装版本调整。

## 核心实现位置

- `MainActivity.kt`: UI、课堂流程、笔记保存、系统语音
- `Model.kt`: 课堂与问答数据结构
- `DeepSeekClient.kt`: API 与分角色 prompts
- `SecretStore.kt`: API Key 本地加密

## 官方文档

- https://api-docs.deepseek.com/api/create-chat-completion/
- https://api-docs.deepseek.com/quick_start/pricing/
- https://api-docs.deepseek.com/guides/asr/
- https://api-docs.deepseek.com/guides/tts/
- https://developer.android.com/develop/ui/compose/bom
