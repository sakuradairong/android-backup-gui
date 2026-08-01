# Root Backup/Restore 修复方案

## 当前基线

当前项目是 root 权限 Android 备份/恢复工具。修复优先级应围绕以下目标排序：

- root 权限下不执行不可信输入。
- 恢复不越界写系统或其他应用数据。
- 备份结果可信，失败不能显示为成功。
- 用户不会误恢复、误覆盖或隐式恢复 Wi-Fi。

当前工作区已有上一轮候选修复的未提交 diff，另有原本存在的 `app/release/AndroidBackupGUI-release.apk` 修改。后续实现前应先决定是否保留候选改动；APK 修改建议单独处理，不和源码修复混在一个提交里。

## 阶段 1：阻断 Root 注入和路径穿越

优先级：P0，必须先做。

涉及文件：

- `RestoreOperation.kt`
- `RestoreScreen.kt`
- `RestoreApkInstaller.kt`
- `RestoreArchiveSafety.kt`
- `RestoreAppDataOps.kt`
- `BackupOperation.kt`
- `BackupConfig.kt`
- `ConfigScreen.kt`

修复点：

- 对所有来自备份文件的包名使用 `PackageName.safe()`，包括 `appList.txt`、备份目录名、Restic snapshot 中的 app list。
- 对 `File(backupDir, pkg)` 做 `canonicalFile` 校验，确保目标目录仍在 `backupDir` 内。
- APK 文件名只允许普通文件名，不允许 `/`、`\`、`.`、`..`、空白或 shell 元字符。
- `pm install -r -t $apkPaths` 中每个 APK 路径必须单独加单引号并 `shellEscape()`。
- `RestoreArchiveSafety` 不能只拒绝绝对路径和 `..`，还必须拒绝 `etc/passwd` 这类相对路径，因为恢复时使用 `tar -C /`。
- 归档恢复白名单必须限定到当前 package 目录，例如 `/data/data/<pkg>/`、`/data/user_de/<user>/<pkg>/`、`/data/media/<user>/Android/data/<pkg>/`。
- 压缩方式从自由文本改为 allowlist：只接受 `zstd` 或 `tar`，其他值归一为安全默认值。
- `chmod`、`cp`、`tar`、`pm` 等 root shell 字符串统一走安全 quoting 规则。

验收标准：

- 恶意 `appList.txt` 中的 `../evil` 被过滤。
- 恶意 APK 名 `a.apk; reboot` 不会进入可执行 shell 语义。
- 恶意 tar entry `etc/hosts`、`/system/bin/x`、`../x` 全部拒绝。
- `Compression_method=';reboot;'` 不会进入目录名或 shell 命令。
- 单测覆盖以上输入。

## 阶段 2：修复备份正确性和失败统计

优先级：P0，必须和阶段 1 同批或紧随其后。

涉及文件：

- `BackupOperation.kt`
- `BackupAppDataOps.kt`
- `RestoreOperation.kt`
- `RestoreAppDataOps.kt`
- `BackupIntegrityChecker.kt`

修复点：

- 删除“APK 未变就按旧 metadata 跳过 app data”的逻辑。应用数据变化不依赖 APK version。
- APK copy 失败不能只 log warning，必须计入失败。
- OBB、external data、SSAID、permissions 的失败应有明确结果模型，不能最后仍显示 app success。
- gzip/tar 参数顺序修正，确保 `-f` 后面紧跟 archive path。
- `chmod -R 0755` 改为保守权限，建议目录 `0700`、文件 `0600`，至少不要给 group/other 读权限。
- integrity check 应针对实际 `backupTargets` 和实际成功包，而不是全部 app 列表。
- 如果外部数据目录不存在，应区分“无数据”与“备份失败”。

建议设计：

- 新增内部结果类型，例如 `BackupStepResult` 或 `PerAppResult`。
- 每个 app 输出 `success`、`partial`、`failed` 三态。
- UI 显示失败步骤列表，例如 `APK 失败`、`外部数据失败`、`权限恢复失败`。

验收标准：

- APK 复制失败时 `successCount` 不增加。
- external data tar 失败时不会显示“完成”。
- gzip 模式实际生成可校验归档。
- 旧 metadata 存在时仍会重新备份 app data。

## 阶段 3：恢复流程安全 UX

优先级：P1，高优先级。

涉及文件：

- `RestoreScreen.kt`
- 后续建议新增 `RestoreViewModel.kt`

修复点：

- 选择备份源后默认不全选应用。
- 提供明确的“全选应用”和“取消全选”。
- 恢复前必须弹确认框，显示应用数量、备份源、目标 userId、是否恢复 Wi-Fi、覆盖数据风险。
- Wi-Fi 恢复改为单独 opt-in，默认关闭。
- 恢复结果必须展示 Wi-Fi 成功/失败。
- `partial` 或失败终态保持 error 色，不在 `isRunning=false` 后变灰。
- Restic 恢复 selected packages 时，避免先下载整个 snapshot 后再过滤，后续可用 include patterns 优化。

验收标准：

- 用户必须至少做一次明确选择才可恢复。
- 点击恢复前会看到覆盖风险确认。
- Wi-Fi 不会在用户未勾选时恢复。
- 部分失败状态在完成后仍明显可见。

## 阶段 4：任务生命周期与取消

优先级：P1，高优先级但改动较大，建议单独 PR/提交。

涉及文件：

- `BackupViewModel.kt`
- `RestoreScreen.kt`
- `BackupService.kt`
- `RootShell.kt`
- 可能新增 `RestoreViewModel.kt`

修复点：

- Restore 逻辑从 `rememberCoroutineScope()` 移到 ViewModel。
- 长任务状态通过 `StateFlow` 暴露，切换底部 tab 不丢状态。
- Foreground service 支持 backup 和 restore 两类任务。
- 通知栏增加取消 action。
- UI 增加取消按钮。
- `RootShell.exec()` 现在 `withTimeout` 包住阻塞 `Shell.cmd().exec()`，不能可靠杀进程；需要支持 active command cancellation。
- Restic 进程也要可取消。

验收标准：

- 切换页面后恢复任务继续可见。
- 用户能取消备份/恢复。
- 取消后不会继续写数据或继续上传。
- 通知进度和 UI 状态一致。

## 阶段 5：凭据与网络安全

优先级：P1。

涉及文件：

- `network_security_config.xml`
- `WebdavTransport.kt`
- `RemoteTransport.kt`
- `SmbTransport.kt`
- `BackupConfig.kt`
- `ConfigViewModel.kt`
- `CredentialProvider.kt`
- `RootShell.kt`
- `RestBridgeRunner.kt`
- `ResticRestBridge.kt`

修复点：

- 默认禁止 cleartext traffic。
- WebDAV 默认要求 `https://`，HTTP 需要显式开关和强警告。
- Basic auth 不得走 HTTP。
- 旧版 `backup_settings.conf` 中的明文 `restic_password`、`restic_backend_pass` 需要迁移到 `PasswordManager` 后重写配置文件。
- 禁止日志输出 token、SSID/SSAID、密码、Authorization。
- `Shell.enableVerboseLogging` 在 release 关闭。
- SMB signing 默认开启，允许兼容性降级但要提示风险。

验收标准：

- `http://` WebDAV 默认无法保存或无法连接。
- 旧配置首次加载后密码进入加密存储，配置文件只剩占位符。
- 日志中搜不到 token/password/Authorization/SSAID 明文。
- lint 不再报全局 cleartext warning，或仅有明确限定域名。

## 阶段 6：Restic streaming 策略

优先级：P2。

涉及文件：

- `BackupViewModel.kt`
- `ResticStreamBackup.kt`
- `ConfigScreen.kt`

决策选项：

- 选项 A：先隐藏或禁用 streaming，因为它不是完整备份。
- 选项 B：保留但 UI 明确标注“实验功能，不包含 OBB、外部数据、Wi-Fi、权限、SSAID”。
- 选项 C：实现与普通备份等价的 streaming，包括 APK、data、OBB、external data、SSAID、permissions、Wi-Fi。

建议：

先选 A 或 B，避免用户误以为 Restic streaming 是完整备份。

验收标准：

- 用户不会把 streaming 误认为完整备份。
- 如果启用 streaming，最终结果必须列出跳过内容。

## 阶段 7：发布与仓库治理

优先级：P2。

涉及文件：

- `app/build.gradle`
- `.gitignore`
- `.github/workflows/*`
- `app/proguard-rules.pro`
- `README.md`
- `SECURITY.md`

修复点：

- 从 git 移除 `app/release/*.apk`，改为 GitHub Release artifact。
- `.gitignore` 增加 `app/release/*.apk`。
- release build 缺签名配置时 fail，不允许静默 unsigned。
- 正式发布前更换 `com.example.androidbackupgui`。
- 启用 R8/minify/shrinkResources，并修正 ProGuard keep 规则。
- 添加 CI：`lintDebug`、`testDebugUnitTest`、`assembleDebug`、coverage threshold。
- 更新 README/SECURITY 版本说明。

验收标准：

- 源码提交不包含 APK 二进制。
- release 构建没有签名信息会失败。
- CI 能阻止 lint/test 失败合并。
- 发布产物有 checksum 和来源记录。

## 测试计划

- 单元测试：`PackageName.safe()`、`RestoreArchiveSafety`、压缩方式 normalize、tar 命令构造。
- 假 RootShell 测试：验证 `pm install`、`chmod`、`cp`、`tar` 命令参数都被正确 quote。
- 集成测试：用临时目录构造恶意备份，验证非法包名和归档被拒绝。
- UI 测试：恢复源加载后默认未选中，Wi-Fi 开关默认关闭，确认弹窗出现。
- 回归测试：zstd/tar 两种压缩方式都能备份并恢复测试 fixture。
- 手工设备测试：owner user 和非 owner user 各跑一遍小应用备份/恢复。

建议执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## 推荐提交拆分

- 提交 1：恢复输入校验、归档白名单、APK install quote。
- 提交 2：备份正确性、tar 参数、失败计数、权限收紧。
- 提交 3：恢复 UI 安全默认值、Wi-Fi opt-in、失败终态显示。
- 提交 4：凭据迁移、HTTPS 默认、敏感日志清理。
- 提交 5：生命周期/取消/Foreground service 重构。
- 提交 6：发布治理、CI、移除 APK artifact。

## 推荐最小闭环

第一批只做阶段 1、阶段 2、阶段 3 的核心部分。这样能先把 root 注入、路径穿越、备份误成功、误恢复这些最危险问题压下去，且变更范围还可控。
