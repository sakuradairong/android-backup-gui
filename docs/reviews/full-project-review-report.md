# Android Backup GUI — 全面审查报告

**审查日期**: 2026-06-06  
**审查范围**: 37 个 Kotlin 源文件 + 8 个布局/资源 XML + AndroidManifest  
**当前状态**: 53 测试全通过，lint 0 错误，编译成功  
**已知问题排除**: 7 项 memory 记录的待处理项已跳过  

---

## 严重程度说明

| 等级 | 定义 |
|------|------|
| **CRITICAL** | 可直接导致数据泄露、root 提权、静默数据损坏。必须立即修复 |
| **HIGH** | 特定条件下可导致敏感数据泄露、错误处理失效或功能严重受限 |
| **MEDIUM** | 风险较低或需复杂攻击链，但应规划修复 |
| **LOW** | 可改进点，非阻塞 |
| **INFO** | 建议性质，无实际风险 |

---

## 审查方法

使用 11 个 ECC 审查技能分三层并行执行：

| 层级 | 技能 | 方向 |
|------|------|------|
| 第一层：安全与正确性 | ecc-security-reviewer, security-review, ecc-silent-failure-hunter | 漏洞检测、输入校验、静默失败 |
| 第二层：架构与代码质量 | ecc-kotlin-reviewer, kotlin-coroutines-flows, ecc-type-design-analyzer, production-audit | 协程安全、类型设计、生产就绪 |
| 第三层：可维护性与用户体验 | ecc-comment-analyzer, ecc-refactor-cleaner, ecc-code-simplifier, accessibility | 死代码、简化、无障碍 |

---

## 发现汇总

| 层级 | 技能 | CRITICAL | HIGH | MEDIUM | LOW/INFO | 总计 |
|------|------|----------|------|--------|----------|------|
| 第一层 | 安全审查 | 2 | 2 | 5 | 3 | 12 |
| 第一层 | OWASP 安全审查 | 0 | 4 | 6 | 11 | 21 |
| 第一层 | 静默失败审查 | 0 | 4 | 12 | 9 | 25 |
| 第二层 | Kotlin 代码审查 | 0 | 1 | 6 | 19 | 26 |
| 第二层 | 协程/Flow 审查 | 0 | 2 | 4 | 5 | 11 |
| 第二层 | 类型设计审查 | 0 | 2 | 8 | 6 | 16 |
| 第二层 | 生产就绪审查 | 0 | 4 | 10 | 4 | 18 |
| 第三层 | 注释审查 | 0 | 0 | 2 | 4 | 6 |
| 第三层 | 死代码清理 | 0 | 4 | 4 | 4 | 12 |
| 第三层 | 代码简化 | 0 | 2 | 7 | 10 | 19 |
| 第三层 | 无障碍审查 | 1 | 4 | 8 | 2 | 15 |
| | **合计** | **3** | **29** | **72** | **77** | **181** |

---

## 顶层 CRITICAL 问题（必须立即修复）

### C1. 凭据明文存储在配置文件中

**文件**: `BackupConfig.kt:69,73,156-157`  
**类型**: Secret 泄露

restic 密码和 SMB/WebDAV 凭据以明文写入 `backup_settings.conf`，位于 `filesDir`。root 环境下，任何进程可读取。UI 中密码字段也未使用 `inputType="textPassword"`。

**建议**: 使用 `EncryptedSharedPreferences`，UI 使用密码掩码输入框。

### C2. 配置文件写入权限不安全

**文件**: `BackupConfig.kt:~144`  
**类型**: 权限滥用

`file.writeText()` 使用系统默认文件权限，未显式设置 owner-only 权限。

**建议**: 保存后调用 `file.setReadable(true, true)` / `file.setWritable(true, true)`。

### C3. TextView 模拟按钮缺少无障碍角色

**文件**: `PackageListAdapter.kt:61-69`  
**类型**: 无障碍

"数据"排除切换使用纯 `TextView` 实现点击交互，TalkBack 无法识别其可点击角色。

**建议**: 改用 `MaterialButton` 或添加 `focusable=true`, `clickable=true`, `contentDescription`。

---

## HIGH 优先修复（29 项）

### 安全（第一层汇总）

| # | 文件 | 行号 | 问题 | 建议 |
|---|------|------|------|------|
| H1 | `ResticRestBridge.kt` | 27 | NanoHTTPD 绑定 0.0.0.0 无认证，局域网可访问 | 改为 `NanoHTTPD("127.0.0.1", 0)` |
| H2 | `RestoreOperation.kt` | 137-149 | tar 解压使用 `-C /`，恶意存档可覆写系统文件 | 添加绝对路径检查，临时目录解压 |
| H3 | `SmbTransport.kt` | 103-109 | SMB 上传大小不匹配仍返回 Success，数据可能静默损坏 | 不匹配时返回 `AppResult.Failure` |
| H4 | `BackupOperation.kt` | 255-257 | `backupUserData` 全失败时返回 `true`，用户看到"成功" | 改为 `return false` |
| H5 | `ResticBackup.kt` | 55-58, 73-77 | `CancellationException` 被空 `catch` 吞没，取消信号丢失 | 加 `catch (e: CancellationException) { throw e }` |
| H6 | `WebdavTransport.kt` | 153-155 | `mkdirs` 完全失败仍返回 `Success(Unit)` | 异常时应返回 `AppResult.Failure` |
| H7 | `RootShell.kt` | 84-87 | `CancellationException` 被 `catch (e: Exception)` 吞没，全局取消失效 | 加 `catch (e: CancellationException) { throw e }` |
| H8 | `ResticRestore.kt` | 78, 107 | 同 H5，CancellationException 被空 catch 吞没 | 重新抛出 CancellationException |
| H9 | `BackupConfig.kt` | 69, 73 | 所有密码未加密存储（OWASP 维度） | EncryptedSharedPreferences |
| H10 | `ui/ConfigViewModel.kt` | 180-183 | 空密码检测仅 `initResticRepo` 中有，其他操作入口缺 | 在所有操作入口添加密码空值检查 |
| H11 | `BackupConfig.kt` | 139-186 | `allowBackup=true` 使 ADB 备份可提取明文配置 | 设为 `false` 或加密 |

### 架构与代码质量（第二层汇总）

| # | 文件 | 行号 | 问题 | 建议 |
|---|------|------|------|------|
| H12 | `RestoreOperation.kt` | 85 | `coroutineScope` 非 `supervisorScope`，单应用失败取消全部 | 改用 `supervisorScope` |
| H13 | `PackageName` 值类 | 全局 | 多处方法签名使用 `String` 而非 `PackageName` | 统一改为 `PackageName` |
| H14 | `UserId` 值类 | 全局 | 业务层使用 `String`/`Int` 而非 `UserId` | 统一切换为 `UserId` |
| H15 | `ResticRestBridge.kt` | 246-257 | `buildV2Json` 手动拼接 JSON，无转义 | 使用 `JSONObject`/`kotlinx.serialization` |
| H16 | `BackupConfig.kt` | 77-137 | 领域模型混合文件 I/O 逻辑（`fromFile`/`toFile`） | 提取到 `BackupConfigSerializer` |
| H17 | `BackupOperation.kt` + `RestoreOperation.kt` | 各 300-500 行 | object 承担过多职责，混合 Shell 命令 + 数据格式 + 文件操作 | 按关注点拆分 |
| H18 | `package-list-adapter` | 33-90 | 程序化创建视图而非 XML，无法热重载 | 改用 XML 布局 |
| H19 | 生产就绪 | 全局 | 大量硬编码中文字符串，strings.xml 完全过时 | 全部移入 strings.xml+国际化 |
| H20 | `app/build.gradle` | 48-53 | Release 构建无混淆（R8/ProGuard） | 添加 `minifyEnabled true` |
| H21 | `app/build.gradle` | 41,43 | 签名密码回退为弱密码 `"android"` | 环境变量未设置时强制构建失败 |
| H22 | `.github/workflows/ci.yml` | - | CI 不运行 `test`，不验证回归 | 添加 `./gradlew test` |
| H23 | `WebdavTransport.kt` | 22-28 | 无超时配置，请求可能永远挂起 | 设置 connect/read/write 超时 |
| H24 | `SmbTransport.kt`, `WebdavTransport.kt` | 全局 | 远程操作无重试策略 | 实现指数退避重试 |

### 可维护性与无障碍（第三层汇总）

| # | 文件 | 行号 | 问题 | 建议 |
|---|------|------|------|------|
| H25 | `MD4Provider.kt` | 全文件 | 整文件死代码，被 `MissingAlgoProvider` 取代 | 删除 |
| H26 | `BackupFragment.kt` | 440-546 | 3 个流式备份方法从未被调用 | 删除或接入 |
| H27 | `RemoteTransport.kt` | 73-75 | `isFileNotFound` 扩展函数从未使用 | 删除 |
| H28 | `AppScanner.kt` | 26-33 | `DataSizes` 数据类从未被填充或读取 | 删除 |
| H29 | `PackageListAdapter.kt` | 76-88 | 卡片点击区域无障碍语义缺失 | 添加状态文字 contentDescription |

---

## 关键模式分析

### 模式 1：CancellationException 被吞没（全局性）

**影响面**: 项目几乎所有协程操作通过 `RootShell.exec`，其 `catch (e: Exception)` 吞没 `CancellationException`。用户取消操作时，正在运行的 shell 命令不会收到取消信号。

**涉及文件**: `RootShell.kt:84-87`, `ResticBackup.kt:55-58,73-77,117-120,130-134`, `ResticRestore.kt:78,107`, `RootShell.kt:63-66`, `ResticWrapper.kt:315-317`

**修复**: 所有空 `catch (_: Exception)` 前加 `catch (e: CancellationException) { throw e }`。

### 模式 2：远程操作失败返回 Success（3 处）

**涉及文件**: `SmbTransport.kt:103-109`, `WebdavTransport.kt:153-155`, `BackupOperation.kt:255-257`

**影响**: 上层调用者无法区分"操作成功"和"操作失败但返回了 Success"。

### 模式 3：PackageName/UserId 值类未被方法签名采用

**涉及文件**: 全局，影响 `AppScanner`, `BackupOperation`, `RestoreOperation`, `StreamingBackup`, `PackageListAdapter`, `BackupProgress`, `RestoreProgress`

**影响**: 值类的类型安全收益完全丧失，编译器无法区分 `PackageName` 和任意 `String`。

### 模式 4：5 个子模块重复 local/remote 分支模式

**涉及文件**: `ResticBackup.kt`, `ResticRestore.kt`, `ResticRepoInit.kt`, `ResticMaintenance.kt`, `ResticSnapshotOps.kt`

**影响**: 每个方法都复制 `if (backend == "local")` 分支，增加维护成本和出错可能。

### 模式 5：BackupFragment 和 RestoreFragment 缺少 onDestroyView

**影响**: ViewPager 场景下，Fragment 视图销毁后协程完成时可能操作已分离的视图。

---

## 正向发现（设计良好的实践）

| 实践 | 文件 | 说明 |
|------|------|------|
| ✅ 密码通过环境变量传递 | `ResticEnvResolver.kt` | 不在命令行中出现，防止 `ps` 窥探 |
| ✅ `shellEscape()` 一致使用 | `RootShell.kt:15` | 所有 shell 拼接参数都经过转义 |
| ✅ `execSafe()` 安全方法 | `RootShell.kt:95-101` | 提供自动参数转义的执行方法 |
| ✅ SharedFlow 用于一次性事件 | `ConfigViewModel.kt:103` | 标准实践 |
| ✅ StateFlow 用于 UI 状态 | `ConfigViewModel.kt:106` | 标准实践 |
| ✅ `repeatOnLifecycle` | `ConfigFragment.kt:75-84` | 正确使用生命周期感知收集 |
| ✅ ResticBinary 双重检查锁定 | `ResticBinary.kt:13-33` | 正确的 `@Volatile` + `synchronized` |

---

## 按文件发现密度

| 文件 | 发现数 | 最严重 |
|------|--------|--------|
| `backup/ResticRestBridge.kt` | 10+ | HIGH |
| `backup/BackupOperation.kt` | 10+ | HIGH |
| `backup/BackupConfig.kt` | 8+ | CRITICAL |
| `root/RootShell.kt` | 5+ | HIGH |
| `backup/ResticBackup.kt` | 4+ | HIGH |
| `backup/SmbTransport.kt` | 4+ | HIGH |
| `backup/WebdavTransport.kt` | 4+ | HIGH |
| `backup/RestoreOperation.kt` | 4+ | HIGH |
| `ui/PackageListAdapter.kt` | 5+ | CRITICAL (a11y) |
| `backup/ResticCommandRunner.kt` | 4+ | MEDIUM |
| `ui/ConfigViewModel.kt` | 4+ | HIGH |
| `backup/StreamingBackup.kt` | 3+ | MEDIUM |

---

## 修复路线图

### 立即修复（CRITICAL）
1. 密码加密存储（EncryptedSharedPreferences）
2. 配置文件权限加固
3. 无障碍：TextView 改为语义化按钮

### 下一个版本（HIGH 优先级）
4. `CancellationException` 全局修复（所有空 catch）
5. ResticRestBridge 绑定 127.0.0.1 + 认证
6. tar 解压路径检查
7. SMB/WebDAV 失败时返回 Failure 而非 Success
8. `supervisorScope` 替代 `coroutineScope`
9. 死代码清理（MD4Provider.kt, 3 个死方法, DataSizes 等）
10. 添加 release R8/ProGuard 混淆
11. CI 添加 `./gradlew test`
12. WebDAV 超时配置
13. 远程操作重试策略
14. 密码 UI 掩码输入
15. 多目录恢复选择

### 规划修复（MEDIUM）
16. `PackageName`/`UserId` 值类全面采用
17. BackupConfig 分离 I/O 逻辑
18. BackupOperation/RestoreOperation 拆分
19. PackageListAdapter 改用 XML 布局
20. 国际化：硬编码字符串移入 strings.xml
21. 释放签名密码加固
22. 前台服务通知进度更新
23. 恢复操作确认对话框
24. API 超时配置
25. `@Serializable` 死注解清理

---

## 统计概览

| 指标 | 值 |
|------|-----|
| 审查技能数 | 11 |
| 审查文件数 | 37 Kotlin + ~15 资源/配置 |
| 总发现数 | 181 |
| CRITICAL | 3 |
| HIGH | 29 |
| MEDIUM | 72 |
| LOW/INFO | 77 |
| 可删除代码 | ~150 行 + 1 个整文件 + ~20 行导入 |
| 生产就绪评分 | 58/100 |
| 测试覆盖率 | 53 测试（持续集成未运行） |
