<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **android-backup-gui** (2510 symbols, 4881 relationships, 175 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/android-backup-gui/context` | Codebase overview, check index freshness |
| `gitnexus://repo/android-backup-gui/clusters` | All functional areas |
| `gitnexus://repo/android-backup-gui/processes` | All execution flows |
| `gitnexus://repo/android-backup-gui/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

<!-- PROJECT_GUIDE -->
# CLAUDE.md — android-backup-gui

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

一个需要 **root 权限** 的 Android 应用备份/恢复工具（APK + 用户数据 + OBB + SSAID + 权限 + WiFi），集成 **restic** 做加密增量备份，远端支持 SMB / WebDAV（通过本地 REST 桥 + NanoHTTPD 翻译）。技术栈：Kotlin 1.9.0、Jetpack Compose + Material 3（Compose Compiler 1.5.1）、ViewModel + StateFlow/SharedFlow、kotlinx-serialization、libsu（Magisk/KernelSU/APatch）、jcifs-ng（方案 A：直接 SMB 客户端，替代 rclone）、sardine-android（WebDAV）。`minSdk=24`、`targetSdk=34`、`compileSdk=34`，Java 17。

> 注意：app id 是 `com.example.androidbackupgui`（历史遗留），改动时须保留以避免破坏升级链。

## 常用命令

所有 Gradle 命令均在仓库根目录运行，使用 `./gradlew`（Gradle 8.2 wrapper）。

```bash
# 构建
./gradlew :app:assembleDebug                # Debug APK（无需签名）
./gradlew :app:assembleRelease              # Release APK（需 keystore + env vars）

# 质量
./gradlew :app:lintDebug                    # Android Lint（基线在 app/lint-baseline.xml）
./gradlew :app:testDebugUnitTest            # 单元测试（Kotest + MockK + JUnit 5）
./gradlew :app:lint :app:test               # lint + test 组合
./gradlew koverHtmlReport                   # Kover 覆盖率报告（排除 BuildConfig/R）

# 清理
./gradlew clean
```

**Release 签名要求**（`app/build.gradle` 强制）：必须存在 `app/release.keystore`，且环境变量 `KEYSTORE_PASSWORD`、`KEY_PASSWORD` 必须非空。CI 在 `.github/workflows/release.yml` 中通过 `KEYSTORE_BASE64` secret 解码 keystore，再注入密码。

**单测约定**：`testOptions.unitTests.all { useJUnitPlatform() }` 已配置；测试文件用 Kotest 风格（`should`/`when`），放在 `app/src/test/java/com/example/androidbackupgui/` 对应包下。

## 架构（按包）

源码根包：`com.example.androidbackupgui`

| 层 | 包 | 关键文件 | 职责 |
|---|---|---|---|
| Activity | 根 | `MainActivity.kt` | 初始化 `RootShell`、`ResticBinary`、`LogUtil`、`PasswordManager`、`MissingAlgoProvider`；setContent `{ AppScaffold() }` |
| UI | `ui/` | `AppScaffold.kt`、`AppNavigation.kt`（`Screen` 枚举：BACKUP/RESTORE/CONFIG/LOG）、`BackupScreen/ViewModel`、`RestoreScreen/ViewModel`、`ConfigScreen/ViewModel`、`LogScreen`、`ProgressBlock` | Compose + Material 3，StateFlow/SharedFlow 暴露状态 |
| 业务-备份 | `backup/` | `BackupOperation.kt`（核心备份编排，524 行）、`BackupService.kt`（Foreground Service）、`BackupServiceBridge.kt`（**Service Intent 通信抽象**，封装 30+ 行 Intent 构建代码）、`BackupConfig.kt`、`BackupAppDataOps.kt`、`BackupFileIO.kt`、`BackupIntegrityChecker.kt`、`BackupProgressTracker.kt`、`ConcurrencyController.kt`（Semaphore：备份 3 / 恢复 2）、`TaskCancellationRegistry.kt`、`WifiManager.kt`、`AppInfo.kt`、`AppInfoCache.kt`、`DomainTypes.kt` | 备份全流程 |
| 业务-恢复 | `backup/` | `RestoreOperation.kt`、`RestoreApkInstaller.kt`、`RestoreAppDataOps.kt`、`RestoreArchiveSafety.kt` | 恢复全流程（路径穿越防护） |
| 业务-restic | `backup/restic/` | `ResticWrapper.kt`（**facade**，对外统一入口，纯化后仅含 restic 业务方法）、`ResticSessionFactory.kt`（**restic session 封装**，独家掌控 `defaultResticWrapper` 可变属性）、`ResticCommandRunner.kt`（ProcessBuilder）、`ResticEnvResolver.kt`（环境变量）、`ResticBackup/Restore/SnapshotOps/Maintenance/RepoInit/StreamBackup.kt`、`BackendExecutor.kt`、`RestBridgeRunner.kt`、`ResticRestBridge.kt`（NanoHTTPD）、`RestBridgeHealthChecker.kt`、`RemoteTransport.kt`、`WebdavTransport.kt`、`SmbTransport.kt`、`ResticJson.kt`、`ResticRetryExecutor.kt` | restic CLI 包装 + REST 桥 |
| 业务-core | `backup/core/` | `AppError.kt`、`AppResult.kt`、`ErrorSuggestionFactory.kt`、`FormatUtil.kt`、`LogSanitizer.kt`、`LogUtil.kt`、`RetryUtils.kt`、**`AppDetailsParser.kt`**（JSON 解析，独立于 restic）、**`RepoUrlBuilder.kt`**（URL 拼接工具）、**`SnapshotAppInfo.kt`**（数据类，零 restic 依赖） | 横切：日志、错误、格式化、纯工具 |
| 业务-scan | `backup/scan/` | `AppScanner.kt`（`pm list packages` + `PackageManager` 解析应用名）、`SsaidCache.kt` | 应用扫描 |
| 业务-security | `backup/security/` | `BinaryResolver.kt`、`CredentialProvider.kt`、`LegacyCredentialMigrator.kt`、`MissingAlgoProvider.kt`（注册 BouncyCastle MD4/AESCMAC，jcifs-ng 首次 SMB 需要）、`PasswordManager.kt`（EncryptedSharedPreferences）、`ResticBinary.kt`（定位 `librestic.so`） | 凭据 + 二进制 + 算法 |
| Root | `root/` | `RootShell.kt`（libsu Shell 封装，timeout=120s）、`BatchShellExecutor.kt`、`String.shellEscape()` | root shell 入口 |
| 资源 | `app/src/main/res/` | `values/`、`drawable/`、`xml/`（FileProvider 配置等）、`mipmap-anydpi-v26/` | 标准 Android 资源 |
| 原生 | `app/src/main/jniLibs/arm64-v8a/` | `librestic.so`（restic 0.17+ 编译进 .so）、`libtar_bin.so`、`libzstd_bin.so` | 原生二进制；`packagingOptions.jniLibs.useLegacyPackaging = true` |
| 文档 | `docs/` | `archive/`（历史审查/优化/计划报告归档） | 阶段计划与安全/无障碍审查报告 |
| 根文档 | `*.md` | `README.md`、`SECURITY.md`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md` | 项目入口文档 |

## 数据流（一次备份）

`用户选择应用` → `AppScanner.scanThirdParty` (pm list packages -3) → 创建 `Backup_<compress>_<userId>/` 目录，写入 `appList.txt` + `app_details.json` → 并行（Semaphore=3）每个应用：cp APK → tar zstd 数据 → tar OBB → 提取 SSAID → 快照缓存图标 → `dumpsys package` 拿权限 → `WifiManager.backup()`（可选）`ResticWrapper.backup()`（→ REST 桥 → SMB/WebDAV）。

## 关键约定

- **凭据安全**：restic 仓库密码通过 `RESTIC_PASSWORD` **环境变量**传递，绝不放命令行参数（避免出现在 `ps`）；`MainActivity` 中初始化顺序为 `PasswordManager.init(this)` 后才能调用 restic。
- **路径穿越防护**：`backup/RestoreArchiveSafety.kt` + `backupApps` 中拒绝 `outputDir` 包含 `/Android/` 路径。
- **原生库加载**：`ResticBinary.prepare(context)` 缓存 `librestic.so` 路径；启动期若 `MissingAlgoProvider.register()` 缺失会让首次 SMB 失败。
- **REST 桥模式**：`ResticRestBridge`（NanoHTTPD，127.0.0.1:random 端口）+ `RemoteTransport`（SMB/WebDAV），让 restic 直接读写远端，无需本地 staging 仓库。
- **取消**：`TaskCancellationRegistry` + `BackupService`（Foreground Service），UI 与通知栏都能取消。
- **可注入 Restic**：`ResticWrapper` 是 class 而非 object，构造函数注入 `ResticCommandRunner/ResticEnvResolver/RestBridgeRunner/BackendExecutor`；`defaultResticWrapper` 单例在 `restic/ResticWrapper.kt:38` 导出。
- **ViewModel 抽象层注入**（v1.18 迭代 1-8 重构）：3 个 ViewModel（`BackupViewModel`/`RestoreViewModel`/`ConfigViewModel`）通过可选构造参数注入抽象层，默认值保证向后兼容：
  - `serviceBridge: BackupServiceBridge = AndroidBackupServiceBridge()`：封装 Service Intent 通信，消除 ViewModel 中 30+ 行 `Intent(...).apply { ... }` 模板代码
  - `resticSessionFactory: ResticSessionFactory = DefaultResticSessionFactory()`：独家掌控 `defaultResticWrapper.{binaryPath,cacheDir,backendDomain}` 三个可变属性，ViewModel 不再直接持有单例引用
  - 单元测试时可注入 mock 实现验证调用序列（无需启动 Android framework）
- **core 包零 restic 依赖**（v1.18 迭代 6 设计原则）：`backup/core/` 包是纯工具层，**禁止 import `backup/restic/` 包**。`SnapshotAppInfo`/`AppDetailsParser`/`RepoUrlBuilder` 三个工具不应耦合 restic 业务对象。Kotlin 不支持嵌套 typealias（如需在 `ResticWrapper` 暴露 `SnapshotAppInfo`，直接 import core 类型即可）。
- **低耦合验证纪律**：每次重构迭代后做 grep 全 main src 扫描——`backup.*` 通配符导入、`defaultResticWrapper.X` 单例赋值、`BackupService.Companion.X` 常量、`BackupOperation` 废弃 shim 引用。8 个迭代中累计发现 5 次遗漏（迭代 5/6/7/8 均找到前次迭代的遗留耦合）。

## 已知版本演进

- **v1.17（当前 1.16，versionCode 16）**：阶段 1-7 安全修复（root 注入防护、路径穿越、网络默认安全、凭据加密、任务取消）、Restic streaming 标识、发布治理、CI。
- 历史重要 commit：`fix(security): 阶段1-3 核心安全修复`、`feat(release): 阶段6-7`、`feat(core): 阶段4-5`、`fix(ui): 进度展示语义化与失败可见性`。
- CI 产物：`android.yml`（debug + lint + test + 上传报告）、`ci.yml`（lint + test + release assemble）、`release.yml`（tag `v*` 触发，签名 + SHA-256 + 自动发版）。

## 调试技巧

- `LogUtil.init(filesDir)` 后日志同时写文件到 `filesDir/logs/`；日志内容经 `LogSanitizer` 脱敏。
- `librestic.so` 找不到时检查 `app/src/main/jniLibs/arm64-v8a/` 是否有对应 ABI 的库。
- Lint 基线在 `app/lint-baseline.xml`，新增 lint 警告需评估后再决定是否更新基线。
- ktlint 风格检查包含在 `./gradlew lint` 中；本地可用 `ktlint.py` 配合 `kotlin-language-server` 做诊断。
- WebDAV 默认强制 HTTPS，HTTP 会被拒；SMB 默认签名开启；这些安全默认值见 `docs/archive/security-review-report.md`。
<!-- END_PROJECT_GUIDE -->

<!-- USER_PREFERENCES -->
## 用户偏好

- **语言**: 所有回复必须使用中文。在任何新对话中都需要自动遵守此要求。
<!-- END_USER_PREFERENCES -->
