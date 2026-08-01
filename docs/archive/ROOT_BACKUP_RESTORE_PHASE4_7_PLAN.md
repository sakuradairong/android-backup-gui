# Root Backup/Restore 阶段 4-7 修复方案

## 目标

本方案承接 `docs/ROOT_BACKUP_RESTORE_FIX_PLAN.md` 中阶段 4、5、6、7，目标是把第一批安全修复后的项目继续推进到可长期运行、可取消、网络默认安全、发布流程可控的状态。

当前基线风险：

- 恢复流程仍运行在 `RestoreScreen` 的 `rememberCoroutineScope()` 内，切换页面或重组后状态不可可靠保留。
- 前台服务 `BackupService` 只有 backup start/stop，没有 restore 任务类型、进度更新和取消 action。
- `RootShell.exec()` 只用 `withTimeout` 包裹阻塞 libsu 调用，超时和协程取消不能可靠杀掉底层 root 命令。
- `ResticCommandRunner.runRestic()` 对非 streaming 命令没有协程取消控制，取消 UI 后 restic 进程可能继续运行。
- `network_security_config.xml` 全局允许 cleartext traffic，WebDAV/Basic auth 可能走 HTTP。
- 旧配置中的明文密码迁移逻辑不完整：`BackupConfig.fromFile()` 已把旧密码字段清空，`CredentialProvider` 很难再拿到原始明文并重写配置。
- Restic streaming 与普通完整备份不等价，但 UI 文案仍容易让用户误以为它是完整备份。
- `app/release/AndroidBackupGUI-release.apk` 被 git 跟踪，release 构建缺少签名时会静默不签名，CI 缺失。

## 总体实施顺序

建议按以下顺序拆成独立 PR/提交，避免生命周期、网络安全和发布治理互相混杂：

1. 阶段 4A：新增 RestoreViewModel，把恢复状态和动作从 Composable 移出。
2. 阶段 4B：统一长任务服务、通知进度和取消入口。
3. 阶段 4C：RootShell、restic、RemoteTransport 支持可靠取消。
4. 阶段 5A：网络安全默认值、WebDAV HTTPS 策略、SMB signing 策略。
5. 阶段 5B：凭据迁移和敏感日志脱敏。
6. 阶段 6：Restic streaming 改为显式实验功能，并写入不完整备份 manifest。
7. 阶段 7：移除 APK artifact、签名强制、R8/ProGuard、CI 和文档治理。

代码实现前，每个要修改的函数/类都应先做影响分析；如果 GitNexus MCP 不可用，至少用符号搜索确认直接调用方和测试覆盖。

## 阶段 4：任务生命周期与取消

优先级：P1，高风险高收益。建议单独提交，避免和网络/发布改动混在一起。

### 涉及文件

- `app/src/main/java/com/example/androidbackupgui/ui/BackupViewModel.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/BackupScreen.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/RestoreScreen.kt`
- 新增 `app/src/main/java/com/example/androidbackupgui/ui/RestoreViewModel.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/BackupService.kt`
- `app/src/main/java/com/example/androidbackupgui/root/RootShell.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/BackupOperation.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/RestoreOperation.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/BackupAppDataOps.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/RestoreAppDataOps.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/WifiManager.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticCommandRunner.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/BackendExecutor.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticBackup.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticRestore.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticStreamBackup.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/RemoteTransport.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/WebdavTransport.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/SmbTransport.kt`
- `app/src/main/AndroidManifest.xml`

### 4A：RestoreViewModel 迁移

问题：`RestoreScreen` 目前持有 `backupDir`、`packages`、`selectedPackages`、`selectedSnapshot`、`isRunning`、进度和恢复执行逻辑。恢复流程由 `rememberCoroutineScope()` 启动，页面切换后状态生命周期不可靠，也无法复用测试。

修复方案：

- 新增 `RestoreUiState`，字段覆盖当前 `RestoreScreen` 的状态：备份源、包列表、选择集合、Restic 快照列表、是否显示快照选择、是否显示确认框、Wi-Fi opt-in、结构化进度、状态文本。
- 新增 `RestoreEvent`，用于一次性错误提示、确认完成、打开 SAF 等 UI 事件。
- 新增 `RestoreViewModel : AndroidViewModel`，持有 `MutableStateFlow<RestoreUiState>` 和当前 `Job`。
- 将以下函数/逻辑迁入 ViewModel：
  - 加载配置文件。
  - 加载默认本地备份目录。
  - 从 SAF path 加载备份目录。
  - 列出 Restic snapshots。
  - 加载选中 snapshot 的 app list 和 app details。
  - 选择/取消选择应用。
  - 切换 restoreWifi。
  - 请求恢复、确认恢复、执行恢复。
- `RestoreScreen` 只负责：收集 state、渲染列表和弹窗、调用 ViewModel 方法。
- `resolveSafTreeUri()` 可先保留在 UI 文件中，返回 path 后交给 ViewModel；后续可移动到小型 helper。

验收标准：

- 切换底部 tab 后恢复页状态不丢失。
- 旋转屏幕或 Compose 重组后不重复执行恢复任务。
- 恢复运行中，按钮禁用状态和进度继续可见。
- `RestoreScreen.kt` 不再直接调用 `RestoreOperation.restoreApps()`、`defaultResticWrapper.restore()` 或 `WifiManager.restore()`。

### 4B：统一长任务前台服务

问题：`BackupService` 当前只有 `ACTION_START_BACKUP` 和 `ACTION_STOP_BACKUP`，通知没有进度更新，也没有取消 action。恢复没有前台服务保护。

修复方案：

- 保留类名 `BackupService` 以减少 manifest 和调用方改动，但语义升级为通用 long-running data sync service。
- 新增 action：
  - `ACTION_START_TASK`
  - `ACTION_UPDATE_TASK`
  - `ACTION_CANCEL_TASK`
  - `ACTION_STOP_TASK`
- 保留旧 `ACTION_START_BACKUP` / `ACTION_STOP_BACKUP` 常量作为内部别名或一次性迁移，避免同批改动过大。
- 新增 extras：
  - `EXTRA_TASK_ID`
  - `EXTRA_TASK_TYPE`，取值 `backup` / `restore` / `restic`
  - `EXTRA_STATUS_TEXT`
  - `EXTRA_PROGRESS_CURRENT`
  - `EXTRA_PROGRESS_TOTAL`
  - `EXTRA_PROGRESS_PERCENT`
- 通知内容：
  - backup：标题 `Android Backup - 备份中`
  - restore：标题 `Android Backup - 恢复中`
  - restic：标题 `Android Backup - Restic 同步中`
  - 当有 total/current 时显示 determinate progress。
  - 当只有 Restic percent 时显示百分比。
  - 否则显示 indeterminate progress。
- 通知增加取消按钮，发送 `ACTION_CANCEL_TASK` 到 service。
- Service 收到取消 action 后广播或调用应用内任务取消入口。推荐最小实现：发送显式 broadcast，ViewModel 注册 `BroadcastReceiver` 或使用进程内 `TaskCancellationRegistry`。

推荐最小架构：

- 新增 `TaskCancellationRegistry` object：
  - `register(taskId: String, cancel: () -> Unit)`
  - `cancel(taskId: String)`
  - `unregister(taskId: String)`
- ViewModel 启动任务时生成 `taskId`，注册 `currentJob?.cancel()` 和底层 command token cancel。
- `BackupService` 收到通知取消 action 时调用 `TaskCancellationRegistry.cancel(taskId)`。

验收标准：

- 备份和恢复都启动 foreground notification。
- 通知栏取消按钮能取消当前任务。
- UI 取消按钮和通知取消按钮效果一致。
- 任务结束、失败或取消后通知消失。

### 4C：可靠取消 root/restic/transport

问题：仅取消 Kotlin coroutine 不足以停止 root shell tar/cp/pm 或 restic 进程。现在 `RootShell.exec()` 的 `withTimeout` 只会让协程超时返回，底层命令可能继续写数据。

修复方案：

- 新增 `OperationCancellation` 或 `CancellableTaskToken`：
  - `taskId: String`
  - `isCancelled: Boolean`
  - `cancel()`
  - `throwIfCancelled()`
  - `registerProcess(process: Process)` / `registerRootPid(pid: Int)`
  - `killActiveChildren()`
- `BackupOperation.backupApps()`、`RestoreOperation.restoreApps()`、`ResticStreamBackup.backup()` 增加可选 token 参数，默认 token 为非取消状态，减少调用方破坏。
- 在每个 app、每个 root 命令前后调用 `coroutineContext.ensureActive()` 和 `token.throwIfCancelled()`。
- `RootShell` 新增 `execCancellable(command, token, timeoutMs)`，长命令优先调用它。
- root 命令包装策略：
  - 给每个命令生成 pid file，例如 `/data/local/tmp/android_backup_gui_<taskId>_<nonce>.pid`。
  - 用 root shell 启动子命令并写入 pid：`( <command> ) & pid=$!; echo $pid > pidfile; wait $pid; code=$?; rm -f pidfile; exit $code`。
  - token cancel 时先 `kill -TERM $pid`，再 `pkill -TERM -P $pid`，短暂等待后 `kill -KILL $pid` 和 `pkill -KILL -P $pid`。
  - 如果设备支持 process group/`setsid`，优先使用进程组杀树；否则使用父子进程 fallback。
- 所有 tar、zstd、cp、pm install、am force-stop、WifiManager root 写入都改为长命令可取消路径。
- `ResticCommandRunner`：
  - 新增 suspend 版本 `runResticCancellable(env, args, token)`。
  - `ProcessBuilder.start()` 后立即注册 process。
  - `CancellationException` 时执行 `destroy()`，等待短时间后 `destroyForcibly()`。
  - `runResticStreaming()` 同样在 finally 中销毁 process 并关闭 stdout/stderr。
  - 非 suspend 的 `runRestic()` 逐步收口到 suspend 版本，调用链不能改完时先只在 backup/restore 主路径使用 suspend 版本。
- `RemoteTransport`：
  - WebDAV/SMB upload/download 循环中加入 `coroutineContext.ensureActive()`。
  - cancel 时关闭 input/output stream，避免继续传输。

UI 行为：

- `BackupScreen` 和 `RestoreScreen` 运行中显示 `取消` 按钮。
- 用户点取消后立即显示 `正在取消...`，禁用二次点击。
- 取消成功后状态为 `已取消`，`progressStage = "partial"` 或新增 `"cancelled"`。
- 取消不是成功，不得显示 `完成`。

验收标准：

- 备份/恢复中点击取消后，新的 app 不再开始处理。
- 正在运行的 tar/zstd/restic 进程被终止。
- 取消恢复时不会继续写 `/data/data`、`/data/user_de`、`/storage/emulated/.../Android/data`。
- restic 上传/恢复取消后，设备上没有继续运行的 restic 子进程。
- 通知、UI、ViewModel state 三者最终状态一致。

建议测试：

- `RestoreViewModelTest`：加载本地目录后默认不选中，选择后确认恢复，取消后状态为 cancelled。
- `BackupViewModelCancellationTest`：fake `BackupOperation` 阻塞时 cancel，断言不显示完成。
- `ResticCommandRunnerCancellationTest`：运行本地 `sh -c "sleep 30"` 后取消，断言进程退出。
- `RemoteTransportCancellationTest`：用 fake InputStream/OutputStream 模拟大文件，取消后循环停止。
- 手工设备测试：备份大应用时取消，执行 `ps -A | grep -E 'tar|zstd|restic'` 确认无残留。

## 阶段 5：凭据与网络安全

优先级：P1。建议在阶段 4 后做，因为阶段 5 会改 Restic/backend 调用链，和取消改动有交集。

### 涉及文件

- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/java/com/example/androidbackupgui/backup/BackupConfig.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/ConfigScreen.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/ConfigViewModel.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/security/CredentialProvider.kt`
- 新增 `app/src/main/java/com/example/androidbackupgui/backup/security/LegacyCredentialMigrator.kt`
- 新增 `app/src/main/java/com/example/androidbackupgui/backup/core/LogSanitizer.kt`
- `app/src/main/java/com/example/androidbackupgui/root/RootShell.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/WebdavTransport.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/RemoteTransport.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/SmbTransport.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/RestBridgeRunner.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticRestBridge.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticCommandRunner.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticEnvResolver.kt`
- `README.md`
- `SECURITY.md`

### 5A：默认禁止明文网络

问题：`network_security_config.xml` 当前全局 `cleartextTrafficPermitted="true"`。WebDAV Basic auth over HTTP 会泄露账号密码。SMB signing 默认关闭。

修复方案：

- `network_security_config.xml` 改为：
  - `<base-config cleartextTrafficPermitted="false">`
  - 保留 system trust anchors。
- `BackupConfig` 新增配置项：
  - `allowInsecureWebdav: Int = 0`
  - `allowInsecureRestServer: Int = 0`
  - `smbSigningMode: String = "required"`，可选 `required` / `preferred` / `disabled`
- `BackupConfig.fromFile()` 和 `toFile()` 读写：
  - `allow_insecure_webdav`
  - `allow_insecure_rest_server`
  - `smb_signing_mode`
- `ConfigScreen`：
  - WebDAV URL 输入提示默认 `https://host/path`。
  - 当 backend 为 WebDAV 且 URL 以 `http://` 开头时，显示 error 文案。
  - 默认禁止保存 HTTP WebDAV。
  - 如果确实需要 HTTP，用户必须开启 `允许不安全 HTTP WebDAV` 开关，并确认风险弹窗。
  - 如果 WebDAV 有用户名/密码，则即使开启 HTTP 也禁止 Basic auth over HTTP，除非后续明确引入更高风险的 developer-only 开关；第一批不建议允许。
  - rest-server 若为 `http://127.0.0.1` 或 `http://localhost` 可允许；非 loopback HTTP 需要 `allowInsecureRestServer=1` 和强警告。
- `RemoteTransport.create()` 增加安全策略参数：
  - `allowInsecureWebdav`
  - `smbSigningMode`
- `WebdavTransport` 初始化时校验 scheme：
  - 非 `https` 且未允许则返回明确失败。
  - `http` + username/password 直接失败。
  - 拒绝 URL userinfo，例如 `https://user:pass@example.com/path`。
- `SmbTransport`：
  - 默认开启 signing，建议 `required`。
  - `preferred` 可尝试 signing，失败后提示用户可降级。
  - `disabled` 仅通过 UI 强警告开启。
  - 不要默认开启 encryption，除非验证 jcifs-ng 与常见 NAS/Windows 兼容；先将 signing 和 encryption 分开配置。

验收标准：

- 新安装默认不允许 cleartext HTTP。
- WebDAV `http://host` 默认不能保存或不能连接。
- WebDAV `http://host` + 用户名/密码永远不能连接。
- WebDAV `https://host` 正常工作。
- SMB signing 默认开启；降级必须有 UI 明确风险提示。
- Android lint 不再报全局 cleartext warning。

### 5B：凭据迁移和配置文件重写

问题：`BackupConfig.fromFile()` 目前把 `restic_password` 和 `restic_backend_pass` 清空，这可以避免继续使用明文，但也导致首次加载旧配置时迁移逻辑可能拿不到原始明文，无法自动写入 `PasswordManager` 并重写配置。

修复方案：

- 新增 `LegacyCredentialMigrator`：
  - 只做配置文件原始文本解析，不放入 `BackupConfig` data class。
  - 识别 quoted/unquoted `restic_password` 和 `restic_backend_pass`。
  - 忽略空值和 `stored-in-keystore`。
  - 如果 `PasswordManager` 尚未有对应密码，则写入 encrypted prefs。
  - 迁移完成后调用 `BackupConfig.toFile(BackupConfig.fromFile(file), file)` 重写配置，确保明文变为占位符。
  - 返回迁移结果：`migratedResticPassword`、`migratedBackendPass`、`rewroteFile`、`error`。
- `ConfigViewModel.load()`：
  - 在 `BackupConfig.fromFile(configFile)` 前调用 migrator。
  - 如果迁移成功，在状态栏显示一次性提示：`已迁移旧版明文密码到加密存储`。
- `ConfigViewModel.importConfig()`：
  - 导入文件写入后立即调用 migrator。
  - UI 提示导入配置中的密码已迁移，不会继续明文保存。
- `CredentialProvider.resolve()`：
  - 删除无法生效的“从 config 明文字段迁移”假设，或只作为单元测试兼容 fallback。
  - 明确优先从 `PasswordManager` 读取。
- `ConfigViewModel.exportConfig()`：
  - 更新注释和 UI 文案。当前 `BackupConfig.toFile()` 写的是占位符，不应再说会导出 plaintext password。
  - 若用户需要导出密码，应另做加密导出，不在本阶段实现。

验收标准：

- 旧配置包含 `restic_password="abc"`，首次加载后 `PasswordManager.getResticPassword()` 为 `abc`。
- 同一配置文件被重写为 `restic_password="stored-in-keystore"`。
- `restic_backend_pass` 同样迁移并重写。
- 导入旧配置后不会在 app 私有目录留下明文密码。
- 新导出的配置不包含真实密码。

### 5C：敏感日志脱敏

问题：部分日志会输出 root command、bridge auth token、backend URL、远端路径，存在泄露 token、Authorization、密码、SSID/SSAID 或本地敏感路径的风险。

修复方案：

- 新增 `LogSanitizer`：
  - `redact(text: String): String`
  - 规则覆盖：
    - `Authorization: Basic ...`
    - `RESTIC_PASSWORD=...`
    - `restic_password=...`
    - `restic_backend_pass=...`
    - `password=...`
    - URL userinfo：`https://user:pass@host` -> `https://<redacted>@host`
    - Wi-Fi `psk=...`、`ssid=...` 可按上下文脱敏。
    - SSAID 值仅显示 hash 前 6 位或完全隐藏。
- `RootShell`：
  - `Shell.enableVerboseLogging = BuildConfig.DEBUG`。
  - timeout/failure 日志不要输出完整命令，或输出 `LogSanitizer.redact(command)`。
- `ResticCommandRunner`：
  - 不记录完整 env。
  - command args 只记录子命令和非敏感 flags；路径或 URL 走 sanitizer。
- `ResticRestBridge`：
  - auth 失败日志不能输出 `authToken` 或 Authorization 片段。
  - `Log.w(TAG, "auth failed")` 即可。
- `RestBridgeRunner`：
  - `auth=${authToken.take(8)}` 改为不记录 token。
- `WebdavTransport`：
  - 不输出 Authorization。
  - URL 日志走 sanitizer，避免未来 URL 带 userinfo。
- `WifiManager` 和 SSAID 相关日志：
  - 不输出 Wi-Fi 密码、完整 SSID、完整 SSAID。

验收标准：

- 单元测试覆盖 sanitizer 规则。
- `grep` 搜索日志语句，不存在直接输出 `authToken`、`Authorization`、`RESTIC_PASSWORD`、`restic_password`、`restic_backend_pass`。
- release 构建中 libsu verbose logging 关闭。

建议测试：

- `LegacyCredentialMigratorTest`：旧密码迁移、占位符忽略、文件重写。
- `NetworkSecurityPolicyTest`：WebDAV HTTP/HTTPS、Basic auth over HTTP、rest-server loopback。
- `LogSanitizerTest`：Authorization、URL userinfo、password key、SSID/SSAID 脱敏。
- `SmbTransportConfigTest`：默认 signing mode 为 required，disabled 必须来自显式配置。

## 阶段 6：Restic streaming 策略

优先级：P2，但建议在阶段 5 后尽快做，因为 README 当前仍把 streaming 描述为“无需本地暂存”的完整能力，容易误导用户。

### 涉及文件

- `app/src/main/java/com/example/androidbackupgui/backup/BackupConfig.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/ConfigScreen.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/BackupViewModel.kt`
- `app/src/main/java/com/example/androidbackupgui/ui/RestoreScreen.kt`
- 后续若阶段 4 已完成：`RestoreViewModel.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticStreamBackup.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticWrapper.kt`
- `README.md`

### 策略选择

推荐选择：选项 B，保留但强制标注为实验功能，并写入不完整备份 manifest。

原因：

- 直接删除/隐藏 streaming 会破坏已有配置中的 `streaming_backup=1` 行为。
- 当前 streaming 实现已不是 FIFO，而是临时目录 `stream_data/` 后由 restic 备份；它仍不是普通完整备份。
- 它目前主要包含 metadata、APK、app data，不完整覆盖 OBB、外部数据、SSAID、permissions、Wi-Fi。
- 阶段 4/5 已经会触碰大量任务和 restic 代码，阶段 6 应优先降低误导风险，而不是重写完整 streaming 引擎。

### 6A：UI 明确实验和限制

修复方案：

- `ConfigScreen` 中 streaming 文案从 `流式备份 (FIFO管道 → restic --stdin)` 改为：
  - `实验性 Restic 临时目录备份`
  - 副文案：`不等同完整备份：不包含 OBB、外部数据、权限、SSAID、Wi-Fi；大应用数据可能被跳过。`
- 开启 switch 时弹确认框，列出不包含内容。
- 确认后才写入 `streamingEnabled = true`。
- 保存配置时如果 streaming 开启，在状态信息中继续提示 `实验功能已启用`。
- 如果用户取消确认，switch 保持关闭。

验收标准：

- 用户不能无提示开启 streaming。
- UI 明确说明 streaming 不是完整备份。
- README 不再写 `FIFO pipe → restic --stdin`，改成当前真实实现和限制。

### 6B：写入 streaming manifest

修复方案：

- 在 `ResticStreamBackup.backup()` 的 `workDir` 根目录写入 `streaming_manifest.json`。
- manifest 建议字段：
  - `schemaVersion: 1`
  - `mode: "restic-streaming-experimental"`
  - `completeBackup: false`
  - `included: ["metadata", "apk", "app_data"]`
  - `excluded: ["obb", "external_data", "permissions", "ssaid", "wifi"]`
  - `maxAppDataBytes: 524288000`
  - `skippedPackages: [{ packageName, reason }]`
  - `createdAtEpochSeconds`
- 记录每个跳过原因：
  - `data_excluded_by_user`
  - `no_data_dirs`
  - `data_too_large`
  - `tar_failed`
  - `apk_copy_failed`
- 如果 streaming 中出现 `tar_failed` 或 `apk_copy_failed`，最终结果不能只显示成功，应显示 partial。

验收标准：

- streaming 快照可通过 `restic dump` 读取 manifest。
- 恢复页面读取到 manifest 时显示 `这是实验性不完整备份`。
- 确认恢复弹窗显示 streaming 缺失项目。

### 6C：恢复侧识别 streaming 限制

修复方案：

- 本地/Restic 加载备份源时尝试读取 `streaming_manifest.json`。
- `RestoreUiState` 增加 `backupCompleteness`：
  - `complete`
  - `streamingExperimental`
  - `unknown`
- 恢复确认弹窗中展示：
  - `备份类型：实验性 streaming`
  - `不会恢复：OBB、外部数据、权限、SSAID、Wi-Fi`
- 如果用户勾选 `restoreWifi` 但 manifest 标明不包含 Wi-Fi，UI 禁止勾选或显示不可用。

验收标准：

- streaming snapshot 不再被展示为完整备份。
- 用户恢复 streaming snapshot 前能看到不可恢复项目。
- Wi-Fi 不会对 streaming snapshot 显示为可恢复。

### 后续完整 streaming 选项

如果未来要做真正完整 streaming，有两条路线：

- 路线 C1：继续使用临时目录，但复用普通备份的所有 artifact writer，保证目录结构与普通备份完全一致，然后 restic 备份目录。这不是严格 streaming，但功能完整。
- 路线 C2：真正按 tar stream 写入 restic，需要重新设计 restore 粒度和 metadata，风险大，不建议近期做。

建议本阶段只做选项 B，不做 C1/C2。

建议测试：

- `ResticStreamBackupManifestTest`：manifest 字段正确、跳过原因正确。
- `ConfigScreenStreamingWarningTest`：开启 switch 会先确认。
- `RestoreStreamingManifestTest`：读取 manifest 后恢复确认显示不完整提示。

## 阶段 7：发布与仓库治理

优先级：P2。安全发布前必须完成。

### 涉及文件

- `.gitignore`
- `app/build.gradle`
- `app/proguard-rules.pro`
- `.github/workflows/android.yml`
- `.github/workflows/release.yml`
- `README.md`
- `SECURITY.md`
- `app/release/AndroidBackupGUI-release.apk`

### 7A：移除 git 中的 APK 二进制

问题：`app/release/AndroidBackupGUI-release.apk` 被 git 跟踪，当前工作区也有未提交二进制修改。源码修复提交不应包含 APK。

修复方案：

- `.gitignore` 增加：
  - `app/release/*.apk`
  - `app/release/*.aab`
  - `app/release/*.idsig`
  - `app/release/*.sha256`
  - `app/release/output-metadata.json`
- 用 `git rm --cached app/release/AndroidBackupGUI-release.apk` 从索引移除，但不删除本地文件。
- 在后续提交中确认 `git diff --cached --stat` 不包含 APK 二进制。
- 发布产物改由 GitHub Release workflow 上传。

验收标准：

- `git status` 不再显示 tracked APK 修改。
- 新生成 APK 不会进入 git diff。
- Release artifact 可从 GitHub Release 下载。

### 7B：Release 签名强制失败

问题：`app/build.gradle` 当前 release 构建只有在 keystore 和 env 都存在时才设置 signingConfig；缺失时会静默继续，存在 unsigned/不可发布产物风险。

修复方案：

- release 构建必须满足：
  - `app/release.keystore` 存在，或通过 CI secret 解码生成。
  - `KEYSTORE_PASSWORD` 非空。
  - `KEY_PASSWORD` 非空。
  - `KEY_ALIAS` 可配置，默认 `release`。
- 如果执行 release task 且缺签名信息，抛出 `GradleException`。
- debug 构建不受影响。
- `README.md` 明确 release 构建所需环境变量。

实现注意：

- 不要把密码写入 Gradle 文件或 git。
- 可用 `gradle.startParameter.taskNames.any { it.lowercase().contains("release") }` 判断是否正在构建 release。
- 如果只是 sync 或 debug task，不应 fail。

验收标准：

- `./gradlew :app:assembleDebug` 正常。
- 没有 keystore/env 时 `./gradlew :app:assembleRelease` 失败且错误明确。
- 提供 keystore/env 时 release 正常签名。

### 7C：正式包名治理

问题：当前 `namespace` 和 `applicationId` 都是 `com.example.androidbackupgui`，不适合正式发布。

修复方案：

- 发布前确定正式 ID，例如 `io.github.sakuradairong.androidbackupgui`。
- 如果已有用户安装过旧包名，改 applicationId 会导致 Android 视为新应用，无法直接覆盖安装，也无法自动继承 app 私有目录数据。
- 推荐策略：
  - 如果尚未正式发布：直接改 `namespace` 和 `applicationId`。
  - 如果已发布：先保留 applicationId，改 namespace 可稍后做；另开迁移公告。
- 本阶段建议先做决策，不和阶段 4/5/6 混入同一提交。

验收标准：

- 正式发布前不再使用 `com.example`。
- README 和 SECURITY 中说明包名变更影响。

### 7D：R8/minify/shrinkResources 和 ProGuard 规则

问题：`app/build.gradle` release 未启用 `minifyEnabled` / `shrinkResources`。`app/proguard-rules.pro` 中部分 keep 规则包名疑似不匹配当前代码路径，例如 restic classes 实际在 `backup.restic` 包下。

修复方案：

- release buildType：
  - `minifyEnabled true`
  - `shrinkResources true`
  - `proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'`
- 校正 keep 规则：
  - `com.example.androidbackupgui.backup.restic.RemoteTransport`
  - `com.example.androidbackupgui.backup.restic.SmbTransport`
  - `com.example.androidbackupgui.backup.restic.WebdavTransport`
  - `com.example.androidbackupgui.backup.restic.ResticWrapper$ResticProgress`
  - `com.example.androidbackupgui.backup.restic.ResticWrapper$BackupSummary`
  - `com.example.androidbackupgui.backup.restic.ResticWrapper$ResticSnapshot`
  - `com.example.androidbackupgui.backup.RestoreOperation$RestoreProgress`
  - `com.example.androidbackupgui.backup.BackupConfig`
  - `com.example.androidbackupgui.backup.core.AppError`
  - `com.example.androidbackupgui.backup.core.AppResult`
- 保留 NanoHTTPD、jcifs-ng、kotlinx.serialization 所需规则。
- 用 release 构建验证 restic JSON parsing、SMB/WebDAV bridge、Config import/export。

验收标准：

- `./gradlew :app:assembleRelease` 在签名配置存在时成功。
- release APK 能启动、扫描、备份一个小应用、读取 Restic status。
- ProGuard 不再引用不存在的类路径。

### 7E：CI 工作流

修复方案：

- 新增 `.github/workflows/android.yml`：
  - trigger：`pull_request`、`push` 到 `main`。
  - JDK 17。
  - Gradle cache。
  - 执行：
    - `./gradlew :app:lintDebug`
    - `./gradlew :app:testDebugUnitTest`
    - `./gradlew :app:assembleDebug`
    - `./gradlew :app:koverXmlReport` 或 `:app:koverVerify`。
  - 上传 lint report 和 test report artifact。
- 在 `app/build.gradle` 配置 Kover verification：
  - 初始 threshold 不要过高，建议 line coverage 先设 20%-30%，后续逐步提高。
  - 排除 Android generated、BuildConfig、R、Compose preview。
- 新增 `.github/workflows/release.yml`：
  - trigger：tag `v*`。
  - 从 GitHub Secrets 解码 keystore。
  - 执行 `assembleRelease`。
  - 生成 `sha256sum`。
  - 上传 APK 和 checksum 到 GitHub Release。

验收标准：

- PR 中 lint/test/assembleDebug 任一失败会阻止合并。
- Release workflow 只在 tag 时运行。
- Release artifact 有 `.sha256`。

### 7F：README / SECURITY 更新

修复方案：

- `README.md`：
  - 更新 streaming 描述，明确实验限制。
  - 更新 WebDAV 默认 HTTPS 要求。
  - 更新 SMB signing 默认开启和降级风险。
  - 更新 release 构建签名要求。
  - 移除“配置文件保存密码”的误导，说明密码在 EncryptedSharedPreferences 中。
  - 更新版本历史，加入阶段 1-7 安全修复摘要。
- `SECURITY.md`：
  - 更新支持版本。
  - 增加 root 权限风险说明。
  - 增加备份文件敏感性说明。
  - 增加 HTTP/WebDAV 明文风险说明。
  - 增加 release 校验方式：下载 APK 后校验 SHA-256。

验收标准：

- README 不再描述已经不准确的 FIFO streaming。
- SECURITY 指导用户校验发布产物。
- 文档中不暗示 HTTP WebDAV 是默认安全选择。

## 统一测试计划

阶段 4-7 全部完成后执行：

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

如果 release 签名配置可用，额外执行：

```bash
KEYSTORE_PASSWORD=<密码> KEY_PASSWORD=<密码> ./gradlew :app:assembleRelease
```

手工设备回归：

- 备份一个小型第三方 app，运行中从 UI 取消。
- 恢复一个小型第三方 app，运行中从通知栏取消。
- 取消后检查 tar/zstd/restic 无残留进程。
- WebDAV HTTPS 仓库初始化、备份、列快照、恢复。
- WebDAV HTTP + Basic auth 确认被阻止。
- SMB 默认 signing 连接一个支持 signing 的服务端。
- streaming 开启时确认弹窗出现，备份后 restore 页显示实验性不完整提示。
- release APK 安装启动，扫描/配置/备份/恢复基本流程可用。

## 推荐提交拆分

- 提交 1：新增 RestoreViewModel，恢复状态迁移到 StateFlow。
- 提交 2：BackupService 支持 backup/restore/restic 任务、通知进度和取消 action。
- 提交 3：RootShell、ResticCommandRunner、RemoteTransport 支持取消。
- 提交 4：WebDAV HTTPS 默认、SMB signing 策略、cleartext 禁止。
- 提交 5：LegacyCredentialMigrator 和敏感日志脱敏。
- 提交 6：Restic streaming 实验标识、manifest、恢复警告。
- 提交 7：移除 tracked APK、签名强制、R8/ProGuard、CI、README/SECURITY。

## 不建议同批处理的事项

- 不要在阶段 4 同时重命名 package/applicationId。
- 不要在阶段 5 同时重写完整 Restic streaming 引擎。
- 不要把 release APK 重新加入源码提交。
- 不要为了兼容 HTTP WebDAV 放宽 Basic auth over HTTP。
- 不要在没有设备验证的情况下直接发布启用 R8 的 release。
