# Android Backup GUI — 完整项目审查报告

**审查日期**: 2026-08-01
**审查范围**: 全部 81 个 Kotlin 源文件（约 14,000 行）+ 23 个测试文件（约 2,800 行）+ Gradle 配置 + CI workflow + 文档
**审查方法**: 构建验证（`testDebugUnitTest` + `koverHtmlReport`）、LSP 类型诊断、jscpd 重复检测、gitleaks 密钥扫描、zizmor CI 安全分析、人工代码走查（安全关键路径）

---

## 一、总体结论

项目整体**质量良好**：构建通过、193 个单元测试全绿、类型层面 0 错误、安全架构经过多轮加固（REST 桥鉴权、路径穿越防护、凭据不落盘等均有完整实现与测试）。主要短板集中在**测试覆盖不足**（总行覆盖 15.5%，restic 核心包仅 10.2%）和**代码重复**（约 120 处 jscpd 重复块），另有若干 CI 供应链卫生问题。

| 级别 | 数量 | 摘要 |
|------|------|------|
| 🔴 HIGH | 1 | restic 核心包测试覆盖极低 |
| 🟠 MEDIUM | 5 | 白名单语义矛盾、重复代码、CI 未固定版本、桥 GET 内存、UI 无测试 |
| 🟡 LOW | 3 | 进程超时潜在地雷、ktlint.py 裸 except、Range 解析健壮性 |

---

## 二、验证结果（✅ 通过项）

### 2.1 构建与测试

- `./gradlew testDebugUnitTest`：**BUILD SUCCESSFUL（5m23s）**，23 个测试类、**193 个测试全部通过**，0 失败 / 0 错误 / 0 跳过
- `./gradlew koverHtmlReport`：BUILD SUCCESSFUL（含 release 变体编译）
- LSP 全量诊断：**81 个主源文件 0 类型错误**（含 BackupOperation、RestoreOperation、ResticRestBridge、WebdavTransport、SmbTransport、ResticCommandRunner、PasswordManager、RestoreArchiveSafety 等关键文件逐一确认）
- gitleaks：**无密钥/凭据泄漏**（全仓库扫描）

### 2.2 安全架构（多轮加固到位）

| 领域 | 实现 | 评价 |
|------|------|------|
| REST 桥 | 绑定 `127.0.0.1:0` 随机端口 + UUID 随机 token（Basic auth，防御纵深） | ✅ |
| Token 传递 | 经 `RESTIC_REST_USERNAME/PASSWORD` **环境变量**传递，不落命令行参数（防 `ps` 窥探） | ✅ |
| 路径穿越 | `RestoreArchiveSafety` 白名单（`/data/data/`、`/data/user_de/`）+ 拒绝 `..`/`./`/绝对符号链接 + `shellEscape` 全量覆盖 | ✅ |
| WebDAV | HTTP 默认拒绝（`allowInsecure` 显式开关）；带凭据的 HTTP **硬拒绝**；URL userinfo 拒绝；OkHttp 默认 TLS 证书校验，全仓库无 trust-all 代码 | ✅ |
| 明文流量 | `network_security_config` 全局禁明文，仅放行 127.0.0.1/localhost | ✅ |
| 凭据存储 | 密码不写配置文件（占位符 `stored-in-keystore`）+ EncryptedSharedPreferences + 原子迁移（临时文件 + `fd.sync` + rename，防崩溃明文残留） | ✅ |
| 日志脱敏 | `LogSanitizer` 覆盖 `password=`、`Authorization:`、URL userinfo | ✅ |
| 配置权限 | 配置文件 `setReadable(true, true)` 属主可读 | ✅ |
| 其他 | `allowBackup=false`、Foreground Service 非导出、SMB 签名默认 required | ✅ |

### 2.3 可靠性设计

- 取消：`TaskCancellationRegistry` + 协程 `isActive` 感知 + 超时 `destroy/destroyForcibly`，流式读取循环感知取消
- 增量备份：APK 版本比对跳过 + 备份后 `snapshots --json` 快照验证（续传损坏修复后的双保险）
- WebDAV 续传：Range 请求走 `RandomAccessFile` 分块读取（修复过数据损坏问题）
- 错误处理：`AppResult` 代数类型 + 失败计数原子递增，单应用失败不中断整体
- 凭据迁移按字段独立替换（修复过 HIGH 问题：迁移失败时明文不再被占位符覆盖丢失）

---

## 三、🔴 HIGH 发现

### H1. restic 核心包测试覆盖极低（风险等级高）

kover 实测行覆盖：

| 包 | 行覆盖 | 分支覆盖 |
|----|--------|----------|
| **全项目** | **15.5%** | **12.5%** |
| backup.core | 46.0% | 33.9% |
| backup.security | 45.1% | 36.5% |
| backup（编排层） | 36.0% | 17.4% |
| **backup.restic** | **10.2%** | **6.3%** |
| backup.scan | 7.0% | 2.9% |
| root | 12.0% | 0% |
| ui（含 ViewModel） | 1.0% | 2.9% |

**风险点**：覆盖最低的 `backup.restic` 恰恰是最复杂、最容易出错的代码——REST 桥（564 行）、三个传输实现、重试执行器、流式备份。历史上两次严重 bug（WebDAV 续传数据损坏、initGuard 泄漏）都出自该包。其余包中仅 6 个类有测试（ResticSessionFactory、RestBridgeRunner、RestBridgeErrorCode、ResticCommandRunner、ResticRetryExecutor 相关），ResticRestBridge 的 blob CRUD 路径、WebdavTransport/SmbTransport 的失败分支基本无覆盖。

**建议**：

1. 优先为 `ResticRestBridge`（可注入 mock transport，纯 JVM 可测）补充 blob 列表/HEAD/GET/Range/POST/DELETE 的成功与失败分支测试——这是投入产出比最高的一处
2. `ResticRetryExecutor` 重试/指数退避路径补测试
3. UI 层至少为 `BackupViewModel`/`RestoreViewModel` 的纯状态机部分补测试（`ui` 包目前仅 1%）
4. 逐步向 80% 行覆盖目标推进（ECC 标准）

---

## 四、🟠 MEDIUM 发现

### M1. `RestoreArchiveSafety.isArchiveSafe` 白名单语义与文档矛盾

`RestoreArchiveSafety.kt`：

```kotlin
// 文档声明："The built-in app data prefixes are always allowed"
val allowedPrefixes = additionalAllowedPrefixes.ifEmpty { BUILTIN_ALLOWED_PREFIXES }
```

传入 `additionalAllowedPrefixes` 时，内置前缀 `/data/data/`、`/data/user_de/` 被**整体替换**而非并集（`isPathAllowed` 用的是并集 `BUILTIN_ALLOWED_PREFIXES + additional`，两处不一致）。

**当前影响**：唯一调用方 `RestoreAppDataOps.restoreData` 传入的恰好是包级别的两个路径，行为反而更严格，未触发实际 bug。
**潜在影响**：未来调用方若按文档理解传入额外前缀（如 OBB 的 `/sdcard/Android/obb/`），会意外拒绝合法数据条目导致恢复失败；若有人"修复"成并集而没注意此行为，则恢复范围被意外扩大。
**建议**：统一为 `BUILTIN_ALLOWED_PREFIXES + additionalAllowedPrefixes` 并集语义（与文档一致），补充测试锁定行为。

### M2. restic 包大量重复代码（jscpd 约 120 处）

主要重复集群：

- `ResticWrapper` ↔ `ResticBackup/ResticRestore/ResticSnapshotOps/ResticMaintenance`：命令构造 + 后端环境 + 结果解析逻辑 8 组重复（12-18 行/组）
- `ResticBackup` ↔ `ResticRestore` ↔ `BackendExecutor`：`withBackend` 调用模板 4 组
- `BackupViewModel` ↔ `RestoreViewModel`：13 组重复块（6-27 行），其中 2 组为同文件镜像
- `ResticCommandRunner` 内部 3 组镜像、`ResticRestBridge` 内部 3 组镜像（GET/HEAD/DELETE 模式相似）
- UI Screen 组件（BackupScreen/RestoreScreen/LogScreen）5 组

**风险**：CLAUDE.md 已记录"低耦合验证纪律"——8 个重构迭代中累计发现 5 次遗漏，重复是遗漏的温床。
**建议**：优先抽取 `ResticBackup/ResticRestore/ResticSnapshotOps/ResticMaintenance` 共用的命令执行模板（如 `executeWithBackend(args, parser)`），再处理两个大 ViewModel 的共享状态机。

### M3. CI workflow 供应链卫生（zizmor 18 处，3 个 workflow）

- 全部 `actions/checkout@v4`、`actions/setup-java@v4`、`actions/upload-artifact@v4`、`softprops/action-gh-release@v1` 均为**可变 tag 未固定 SHA**（供应链接管风险）
- 3 个 workflow 均无 `permissions:` 块（GITHUB_TOKEN 权限过宽，zizmor: excessive-permissions）
- release.yml 的 `setup-java` gradle cache 存在**缓存投毒**面；上传 lint/test 报告产物（artipacked 提示，实际不含凭据，低危）
- 建议：`permissions: contents: read`（release 加 `contents: write`）+ action 固定 SHA + 可选依赖 `dependabot` 自动升级

### M4. REST 桥 GET 全量下载整读内存

`ResticRestBridge.handleGetBlob`（L468）非 Range 请求路径：`tempFile.readBytes()` 将整个 blob 读入内存。restic pack 文件可达 16-32MB，低端设备存在 OOM 风险（Range 路径已用 `RandomAccessFile`，但全量路径未流式化）。config GET（L271）文件小，无碍。
**建议**：全量路径改用 `RandomAccessFile` 或流式 `newChunkedResponse` 直接喂文件流，与 Range 路径统一。

### M5. UI 层无测试 + 巨型 ViewModel

- `ui` 包行覆盖 1.0%（仅 `StageDisplayNameTest` 一个纯函数测试）
- `RestoreViewModel.kt` 788 行、`BackupViewModel.kt` 520 行、`ConfigViewModel.kt` 417 行——状态机 + 网络编排 + 字符串拼接混居，且与 M2 重复问题叠加
**建议**：把两个 ViewModel 共用的"快照读取/应用列表解析"逻辑下沉为可测纯函数（`loadResticAppDetails`/`tryDump` 已是好雏形），并为状态机补测试。

---

## 五、🟡 LOW 发现

### L1. `waitForCompat` 60 秒硬超时是潜在地雷

`ResticCommandRunner.waitForCompat(deadlineMs = 60_000)` 默认 60 秒后 `destroy()` 进程。当前因调用顺序（先阻塞读 stdout 至 EOF 再 wait）实际不触发，但任何"先 wait 后读"的重构都会让长备份（>60s 是常态）被强杀。
**建议**：改为无限等待 + 由取消机制（`CancellationException` → destroy）驱动，消除隐性上限。

### L2. `ktlint.py` 裸 except（lens blocking 项）

L103 `except: proc.kill()` 裸捕获会吞掉 `SystemExit`/`KeyboardInterrupt`；另有 sleep 轮询与若干 print。属开发辅助脚本，影响面小，顺手改为 `except Exception` 即可。

### L3. Range 头解析健壮性

`handleGetBlob` 对畸形 Range（如 `bytes=abc-`）静默退化为全量下载；restic 不会发送畸形头，属防御性小缺口，可在解析失败时返回 416。

---

## 六、Git / 仓库卫生

- 上一轮已完成：21 个历史报告归档至 `docs/archive/`、工具残留（`.omp/`、`.codegraph/`、`.pi/`）移出跟踪并 gitignore
- 远程仍有 6 个 agent 工作分支未清理（`codex-3t067s`、`gja5gz-codex/-split-apk`、`gsyq9c-codex/-backupapps`、`fix/shell-escape-and-config-export`、`refactor/compose-ui`、`codex`），如已合入可删除
- 当前分支 `refactor/15-iteration-low-coupling-cleanup` 领先 origin 1 个提交（归档提交未推送）

---

## 七、优先级建议

| 优先级 | 动作 |
|--------|------|
| P0 | 无阻断性缺陷，可正常发版 |
| P1 | 为 `ResticRestBridge` 补 blob CRUD 测试（H1 的突破口） |
| P1 | 修复 `isArchiveSafe` 白名单并集语义 + 测试（M1，安全相关） |
| P2 | CI：permissions 收敛 + action 固定 SHA（M3） |
| P2 | REST 桥 GET 流式化（M4） |
| P3 | 抽取 restic 命令模板与 ViewModel 公共逻辑（M2/M5） |
| P3 | waitForCompat 去除硬超时（L1） |
