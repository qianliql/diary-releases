# 日记 0.0.0

面向 ColorOS 手机的原生 Android 个人日记，Android 12+，Kotlin + Jetpack Compose + 本机 SQLite。

## 主要能力

- 时间线、日历、中文子串搜索、心情与标签。
- 同一天多篇日记；可选标题、补写日期；文字、照片、录音内容块排序。
- 自动本机草稿，完成后发布；编辑已有日记时保留独立草稿，原文在完成前保持可读。
- 系统拍照保存相册；本机相册照片保留持久 URI 引用，丢失时提示。
- 16 kHz 单声道 WAV 本机录音和播放。
- 可选 Whisper tiny INT8 模型：默认不安装，设置中主动下载、校验、安装、停用、删除或重装。录音与既有转写独立于模型。
- 密码加密完整备份、校验后恢复、合并去重和冲突副本；PDF 阅读导出；30 天本机回收站。
- 本地每日提醒（默认关闭）、每月备份提醒（默认开启，需系统通知权限）。

## 本地与联网边界

只有主动检查应用更新、检查模型更新、安装模型时使用 `HttpURLConnection` 访问 GitHub。没有账号、统计 SDK、云端语音服务或自动网络请求。关闭 Android 云备份及系统自动迁移。手动备份可由用户保存到自己选定的位置。

照片进入系统相册后，是否由相册同步由 ColorOS 设置决定。删除相册原图可能使日记缺图。备份时收集可读取的原图；恢复照片在应用私有目录。

## 构建

需要 JDK 17、Android SDK 36、Android Build Tools、Gradle 8.13；依赖版本已固定。仓库内包含经过哈希校验的官方 sherpa-onnx AAR（维护者 Hugging Face 镜像构建，SHA256 `8c209555329fce0cad9df44ac1535c8d55b0c86f51dc3f2080246b7f0699caf6`）。

1. 在 `local.properties` 中设置 `sdk.dir`，或设置 `ANDROID_HOME`。
2. `gradlew.bat assembleDebug testDebugUnitTest` 构建并测试。
3. `gradlew.bat connectedDebugAndroidTest` 在连接的 Android 设备上测试。`NativeModelTest` 仅在手动放入可选模型和测试录音后运行。
4. 发布构建：`gradlew.bat assembleRelease -PtargetAbi=arm64-v8a`。`signing.properties` 包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，必须使用同一把私有密钥持续签名。密钥与本机配置不在源代码压缩包中。

调试构建为独立的“日记·测试”（包名后缀 `.qa`），不具备 INTERNET 权限，可在手机 Wi-Fi 正常连接时验证本地功能。模型测试通过 USB 手动放入已校验的模型，测试后删除；设置里的在线安装须在正式版验证。

## 每次版本同步发布

版本从 0.0.0 开始，每次迭代同步 GitHub [`qianliql/diary-releases`](https://github.com/qianliql/diary-releases) 和 Gitee [`qianlihuo/diary`](https://gitee.com/qianlihuo/diary)。两边保留相同 APK、源码压缩包、说明、测试报告、版本标签和 SHA256。发布过程见 [AGENTS.md](AGENTS.md)，使用 `tools/package_release.py` 与 `tools/publish_release.py`；任何一边失败都不能报告为双平台发布完成。发布凭据仅从本机凭据管理器或环境变量读取。

正式版本码为 `1 + major*10000 + minor*100 + patch`，首版 0.0.0 对应 1，minor/patch 均为 0–99。版本更新使用同一签名覆盖安装。

如果 Windows JDK 出现 UnixDomainSockets 本地连接错误，可在当前构建进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/diary-no-socket-dir`，目录应保持不存在，让 Java 回退到 TCP；这不需要修改系统安全策略。

## 可选模型发布

`tools/prepare_model.py <输入目录> <发布目录>` 对 encoder/decoder 的上游 SHA256 进行校验，再生成可复现模型 ZIP、逐文件校验目录及应用内模型目录。输入目录需包含 `encoder.onnx`、`decoder.onnx`、`tokens.txt`、Whisper 的 `LICENSE.txt`。模型来自 `csukuangfj/sherpa-onnx-whisper-tiny` 的 tiny INT8 文件，语言固定普通话。

模型 ZIP、`model-catalog.json`、许可文件发布至 `qianliql/diary-releases` 的 `models-v1` Release。APK 发布使用版本标签，系统安装器负责覆盖更新。`UpdateClient` 读取最新非预发布版本；首个预发布测试包不会被视为稳定更新。

基础 APK 不包含 `.onnx` 权重。模型存入 `noBackupFilesDir/speech-model`，完全排除于手动备份。下载采用临时目录和完整 SHA256 校验，安装成功才替换旧模型；失败或取消时保留原模型。

## 数据格式

数据库版本 1，日记与内容块使用稳定 UUID。备份格式 `DIARYBK1`，PBKDF2-HMAC-SHA256（600,000 次）、随机盐及 AES-256-GCM 认证加密，恢复到临时文件并验证认证标签后才导入。

媒体按 SHA256 去重，档案格式版本化。合并按日记原始标识和规范化内容指纹判断，相同跳过、冲突复制并标注备份来源，重复导入相同冲突版本保持幂等。语音模型不进入备份。

通知使用系统非精确本地闹钟，可能被 ColorOS 省电策略延迟。永久清理只删除应用私有的无引用媒体，保留系统相册原图。
