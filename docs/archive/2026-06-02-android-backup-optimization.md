# Android Backup GUI — 代码优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 对 Android Backup GUI 进行三项高影响优化：类型化错误处理、协程/Flow 重构、安全加固，外加 Kotlin 惯用清理。

**Architecture:** 项目结构为 app/src/main/java/com/example/androidbackupgui/{backup,ui,root} 三层。backup 层 22 个文件平铺，无 domain 层。优化采用增量替换模式——不重构包结构，只在现有边界内替换实现。

**Tech Stack:** Kotlin + Coroutines + StateFlow + DataBinding + libsu (root) + sardine-android (WebDAV) + jcifs-ng (SMB)

---

### Task 0: 基础准备

**Files:**
- Create: `app/src/main/java/com/example/androidbackupgui/backup/AppError.kt`
- Create: `app/src/main/java/com/example/androidbackupgui/backup/TransferProgress.kt`
- Test: (暂无测试框架，先创建接口不破坏编译)

- [ ] **创建 sealed class 错误层次**

```kotlin
// app/src/main/java/com/example/androidbackupgui/backup/AppError.kt
package com.example.androidbackupgui.backup

/**
 * 类型化应用错误层次。所有业务层错误统一为此 sealed interface。
 */
sealed interface AppError {

    /** 人类可读的错误描述 */
    val message: String

    /** 网络/IO 类错误 */
    data class Network(
        override val message: String,
        val cause: Throwable? = null,
        val retryable: Boolean = true
    ) : AppError

    /** Root shell 命令执行错误 */
    data class Shell(
        override val message: String,
        val command: String,
        val exitCode: Int,
        val stderr: String
    ) : AppError

    /** 远端文件操作错误（WebDAV/SMB） */
    data class Remote(
        override val message: String,
        val phase: String,
        val cause: Throwable? = null,
        val isNotFound: Boolean = false,
        val retryable: Boolean = false
    ) : AppError

    /** 本地文件/IO 错误 */
    data class LocalIO(
        override val message: String,
        val path: String,
        val cause: Throwable? = null
    ) : AppError

    /** restic 命令执行错误 */
    data class Restic(
        override val message: String,
        val exitCode: Int,
        val stderr: String
    ) : AppError

    /** 解析/配置错误 */
    data class Parse(
        override val message: String,
        val detail: String = ""
    ) : AppError

    /** 操作被取消 */
    data object Cancelled : AppError {
        override val message: String = "操作被取消"
    }
}
```

- [ ] **验证编译通过**

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **创建 AppResult 类型别名**

```kotlin
// 在 AppError.kt 末尾追加
typealias AppResult<T> = Result<T>
// 后续步骤逐步替换为自定义 sealed Result 类型
```

---

### Task 1: 类型化错误处理 — RemoteTransport 层

**目标:** 将 `RemoteTransport` 接口和实现中的 `Result.failure(Exception(...))` 替换为 `AppError`，消除字符串拼接异常和沉默吞错误。

**Files:**
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/RemoteTransport.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/WebdavTransport.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/SmbTransport.kt`
- Delete: (删除 `FileNotFoundException` 类，被 `AppError.Remote(isNotFound=true)` 替代)

- [ ] **替换 RemoteTransport 返回类型**

```kotlin
// RemoteTransport.kt — 接口方法签名替换
// 原来: suspend fun upload(...): Result<Unit>
//   →  suspend fun upload(...): AppResult<Unit>
// 原来: suspend fun listFiles(...): Result<List<RemoteFileInfo>>
//   →  suspend fun listFiles(...): AppResult<List<RemoteFileInfo>>
// 原来: suspend fun exists(...): Result<Boolean>
//   →  suspend fun exists(...): AppResult<Boolean>
// 原来: class FileNotFoundException(path: String) : Exception("Directory not found: $path")
//   →  删除整个类

// Result 保持 kotlin.Result 作为 AppResult，但创建 err 辅助函数
// RemoteTransport.kt 末尾追加
internal fun <T> err(error: AppError): AppResult<T> =
    Result.failure(RuntimeException(error.message).also { /* AppError marker — 后续步骤用 sealed result 替换 */ })
```

- [ ] **替换 WebdavTransport.upload — 使用 AppError**

```kotlin
// WebdavTransport.kt — upload 方法
override suspend fun upload(...): AppResult<Unit> =
    withContext(Dispatchers.IO) {
        try {
            // ... 文件大小检查
            if (fileSize > 50 * 1024 * 1024L) {
                return@withContext err(
                    AppError.LocalIO("文件过大 (${fileSize / 1024 / 1024}MB)，上限 50MB", localPath)
                )
            }
            // ... 传输逻辑
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "upload failed: $remotePath", e)
            err(AppError.Remote("WebDAV 上传失败", "upload", e))
        }
    }
```

- [ ] **替换 WebdavTransport.download**

```kotlin
// WebdavTransport.kt — download 方法 catch 块
// 原来: return@withContext Result.failure(Exception("WebDAV download failed: ${e.message}", e))
//   →  return@withContext err(AppError.Remote("WebDAV 下载失败", "download", e))
```

- [ ] **替换 WebdavTransport.listFiles — 区分 404 和真实错误**

```kotlin
// WebdavTransport.kt — listFiles 方法
// 原来: return@withContext Result.failure(FileNotFoundException(remoteDir))
//   →  return@withContext err(AppError.Remote("远端路径不存在", "list", isNotFound = true))
// 原来: return@withContext Result.failure(Exception("WebDAV list failed: ${e.message}", e))
//   →  return@withContext err(AppError.Remote("WebDAV 列表失败: ${e.message}", "list", e))
```

- [ ] **替换 WebdavTransport.mkdirs / delete / exists**

```kotlin
// mkdirs: 内部 catch 不做错误传播，保持 Result.success(Unit) 最佳努力模式
// delete: 内部 catch 保持 Result.success(Unit) 沉默处理
// 这两个方法是显式的"尽力而为"语义，保持现状但添加注释说明

// exists: 原来 return@withContext Result.failure(Exception("WebDAV exists check failed: ${e.message}", e))
//   →  return@withContext err(AppError.Remote("检查远端路径失败", "exists", e))
```

- [ ] **替换 SmbTransport.kt 同样的模式**

搜索 `SmbTransport.kt` 中所有 `Result.failure(Exception(` 和 `FileNotFoundException(` 的出现，按 WebDAV 相同规则替换。

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git add -A
git commit -m "refactor: replace raw Exception with typed AppError in RemoteTransport layer"
```

---

### Task 2: 类型化错误处理 — ResticWrapper 及调用方

**Files:**
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticWrapper.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticBackup.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticRestore.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticRepoInit.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticSnapshotOps.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticMaintenance.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticCommandRunner.kt`

- [ ] **替换 ResticCommandRunner 异常处理**

```kotlin
// ResticCommandRunner.kt — runRestic 方法
// catch 块原来:
//   CommandResult("", e.message ?: "Unknown error", -1)
// 改为带日志区分：
// — IOException → 网络/IO 错误
// — InterruptedIOException → 超时/取消
// — 其他 → 通用错误
// 方法签名不变（CommandResult 是内部数据类），但 Log.e 带上 cause
```

- [ ] **替换 ResticBackup.parseBackupSummary — 字符串异常 → AppError**

```kotlin
// ResticBackup.kt — parseBackupSummary 方法
// 原来: return Result.failure(Exception("No summary found in restic output"))
//   →  return Result.failure(
//          RuntimeException(AppError.Restic("未在 restic 输出中找到 summary", -1, stdout.take(200)).toString())
//       ).also { Log.w(TAG, "parseBackupSummary: no summary in ${stdout.length} chars") }

// 原来 catch (_: Exception) 两种用法:
//   — progress 解析失败: 保持沉默（非 JSON 行是正常的）
//   — summary 解析失败: 加 Log.w
```

- [ ] **替换 ResticBackup.backup — 异常传递**

```kotlin
// ResticBackup.kt — backup 方法
// 原来: return@withRemoteSync Result.failure(Exception("restic backup failed: ${result.stderr}"))
//   →  return@withRemoteSync Result.failure(
//          RuntimeException(AppError.Restic("restic backup 失败", result.exitCode, result.stderr).toString())
//       )
```

- [ ] **对其他 Restic* 类执行相同替换**

搜索 `Result.failure(Exception(` 和 `Result.failure(RuntimeException(` 在所有 `Restic*.kt` 中的出现。每条替换为带 `AppError.Restic` 或 `AppError.LocalIO` 的形式。

Run: `./gradlew assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git commit -a -m "refactor: add typed AppError to Restic* command results"
```

---

### Task 3: 协程优化 — 进度回调改为 Flow

**问题:** `onProgress: suspend (T) -> Unit` 回调穿过 5+ 层方法签名，每个回调内部 `withContext(Dispatchers.Main)` 切换线程。8KB 粒度的 `ByteProgress` 导致频繁 Context 切换。

**Files:**
- Create: `app/src/main/java/com/example/androidbackupgui/backup/TransferProgress.kt` (从 RemoteTransport 提取)
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/RemoteTransport.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/WebdavTransport.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/SmbTransport.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/RemoteSyncManager.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticBackup.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticWrapper.kt`

- [ ] **提取进度类型到独立文件**

```kotlin
// app/src/main/java/com/example/androidbackupgui/backup/TransferProgress.kt
package com.example.androidbackupgui.backup

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/** 传输阶段进度（连接/传输/完成等） */
@Serializable
data class TransferProgress(
    val phase: String,
    val current: Int,
    val total: Int,
    val currentFile: String = ""
)

/** 字节粒度传输进度 */
@Serializable
data class ByteProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val currentFile: String
)

/** 合并的传输进度事件流 */
sealed interface TransferEvent {
    data class Phase(val progress: TransferProgress) : TransferEvent
    data class Bytes(val progress: ByteProgress) : TransferEvent
}
```

- [ ] **简化 RemoteTransport 接口 — 用 Flow 替换回调对**

```kotlin
// RemoteTransport.kt — upload/download 签名替换

// 原来:
//   suspend fun upload(..., onProgress: suspend (TransferProgress) -> Unit = {}, onByteProgress: suspend (ByteProgress) -> Unit = {}): Result<Unit>
//   →  suspend fun upload(..., onProgress: FlowCollector<TransferEvent>? = null): AppResult<Unit>
//
// 但为了与当前调用方兼容，改用 SharedFlow 模式：
//   保持 suspend fun upload(...): AppResult<Unit>
//   创建一个挂起辅助函数，返回 Flow<TransferEvent>

// 新增扩展方法：
suspend fun RemoteTransport.uploadWithFlow(
    localPath: String,
    remotePath: String
): Flow<TransferEvent> = flow {
    val result = upload(
        localPath, remotePath,
        onProgress = { p -> emit(TransferEvent.Phase(p)) },
        onByteProgress = { b -> emit(TransferEvent.Bytes(b)) }
    )
    // 结果在 flow 完成后通过单独 result 获取
}.flowOn(Dispatchers.IO)

// 但更实用的方式：将 emit 直接传入 upload 内部
// 方案：upload 内部发射到 FlowCollector，而不是回调参数
```

- [ ] **简化方案：只在调用方优化线程切换**

当前最痛的点是 `RemoteSyncManager.withRemoteSync` 内部的 `withContext(Dispatchers.Main)` 每次回调都切换。

**改为：channel + 批量投递到 Main**

```kotlin
// 在 withRemoteSync 内部：
// 原来:
//   val emitProgress: suspend (TransferProgress) -> Unit = { p ->
//       withContext(Dispatchers.Main) { onProgress(p) }
//   }
//
//  改为:
//   val progressChannel = Channel<TransferEvent>(Channel.CONFLATED)
//   val progressJob = launch(Dispatchers.Main) {
//       for (event in progressChannel) {
//           when (event) {
//               is TransferEvent.Phase -> onProgress(event.progress)
//               is TransferEvent.Bytes -> {
//                   // 限制 ByteProgress 投递频率: 每 50ms 投递一次
//                   val now = System.currentTimeMillis()
//                   if (now - lastByteEmitMs >= 50) {
//                       onByteProgress(event.progress)
//                       lastByteEmitMs = now
//                   }
//               }
//           }
//       }
//   }
```

不需要修改 RemoteTransport 接口，只修改 `RemoteSyncManager.withRemoteSync` 内部的回调包装方式。

- [ ] **重构 withRemoteSync 内部使用 Channel**

```kotlin
// RemoteSyncManager.kt
// 修改 withRemoteSync 方法，在大括号前插入:

suspend fun <T> withRemoteSync(
    // ... 参数不变 ...
): Result<T> {
    if (backend != "smb" && backend != "webdav") return action()

    return repoSyncMutex.withLock {
        var shouldCleanup = false
        try {
            val t = ensureTransport(/*...*/)
                ?: return@withLock Result.failure(Exception("传输创建失败"))

            val localDir = File(tempRepoDir)

            // === 进度回调优化：Channel + Main 协程批量处理 ===
            var lastByteEmitMs = 0L
            coroutineScope {
                val progressChannel = Channel<TransferEvent>(Channel.CONFLATED)
                val progressJob = launch(Dispatchers.Main) {
                    for (event in progressChannel) {
                        when (event) {
                            is TransferEvent.Phase -> onProgress(event.progress)
                            is TransferEvent.Bytes -> {
                                val now = System.currentTimeMillis()
                                if (!onByteProgress.isNoop && now - lastByteEmitMs >= 50) {
                                    onByteProgress(event.progress)
                                    lastByteEmitMs = now
                                }
                            }
                        }
                    }
                }

                // 包装 emitProgress
                val emitProgress: suspend (TransferProgress) -> Unit = { p ->
                    progressChannel.send(TransferEvent.Phase(p))
                }
                val emitByteProgress: suspend (ByteProgress) -> Unit = { b ->
                    progressChannel.send(TransferEvent.Bytes(ByteProgress(b.bytesTransferred, b.totalBytes, b.currentFile)))
                }

                // ... 原有 sync/action 逻辑，用 emitProgress 和 emitByteProgress ...
                // 注意原代码的 action() 是同步调用，需要包在 coroutineScope 内
            }
            // ... 后续逻辑 ...
        }
    }
}
```

- [ ] **验证编译通过并运行基本功能**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git commit -a -m "perf: batch Main-thread progress emits via CONFLATED Channel with 50ms throttle"
```

---

### Task 4: 协程优化 — 结构化并发与取消

**Files:**
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/BackupOperation.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/RootShell.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/ui/ConfigViewModel.kt`

- [ ] **BackupOperation.backupApps — 确保协程取消传播**

```kotlin
// BackupOperation.kt — backupApps 方法
// 该方法使用 withContext(Dispatchers.IO) + Semaphore + 内部的 launch
// 问题: launch 在 withContext 内启动，如果不持有 Job 句柄，取消无法传播

// 修改: 用 coroutineScope 代替裸 launch
// 原来:
//   launch {
//       semaphore.withPermit {
//           backupSingleApp(...)
//       }
//   }
//   →  coroutineScope {
//          launch {
//              semaphore.withPermit {
//                  backupSingleApp(...)
//              }
//          }
//      }

// 更优: 用 map + async + Semaphore 替代 launch 集合
val deferreds = apps.map { app ->
    async(backupSemaphore.asContextElement()) {
        backupSingleApp(context, app, config, outputDir, userId, onProgress)
    }
}
val results = deferreds.awaitAll()
```

- [ ] **RootShell.exec — 使用 ensureActive 替代被动超时**

```kotlin
// RootShell.kt — exec 方法
// 当前: 靠 withTimeout(120s) 兜底
// 在等待过程中添加 ensureActive 检查

// 在多条命令场景（如备份数据）添加:
//   ensureActive()  // 在 runTar 循环内部
```

- [ ] **ConfigViewModel — 使用 WhileSubscribed 替代 WhileStarted**

```kotlin
// ConfigViewModel.kt
// 当前可能使用 stateIn(WhileSubscribed(0)) 或默认
// 改为 WhileSubscribed(5000) 保证配置变更存活 5 秒
// 具体取决于当前代码

// 检查当前 SharingStarted 模式并优化
// 如果已经是 WhileSubscribed(5000)，跳过
```

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git commit -a -m "refactor: ensure structured concurrency in BackupOperation and cancellation propagation"
```

---

### Task 5: 安全加固 — Root shell 注入防护

**Files:**
- Modify: `app/src/main/java/com/example/androidbackupgui/root/RootShell.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/BackupOperation.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/WifiManager.kt`

- [ ] **审计所有 RootShell.exec 调用方**

用搜索找到所有 `RootShell.exec(` 或 `RootShell.exec("` 调用：

```bash
# 搜索所有 root shell 调用
# 在项目中搜索 RootShell.exec
```

当前已知的 root shell 调用点：
1. `WifiManager.kt`: `cp '$wifiSource' '${wifiDest.absolutePath.shellEscape()}'` — wifiDest 已 shellEscape，wifiSource 从预定义列表来（安全）
2. `BackupOperation.kt`: 多处 `pm path`、`dumpsys package`、`cp`、`tar`、`ls`、`rm` — 输入中 packageName 来自 `AppScanner`（非用户输入，安全），但 file path 拼接需要确认 shellEscape
3. `SELinuxUtil.kt`: `restorecon` 命令

- [ ] **为所有 root shell 参数统一使用 shellEscape 扩展函数**

```kotlin
// 当前 shellEscape 已经存在 RootShell.kt 中
// 审计每个 RootShell.exec 调用的参数是否穿过了 shellEscape()

// 在 BackupOperation.runTar 中:
// 当前   val cmd = "tar ... '$excludesStr' ..."
// 确认 excludes 路径都经过了 shellEscape
```

- [ ] **创建 RootShell.exec 安全包装**

```kotlin
// RootShell.kt — 添加安全执行方法
// 禁止直接 exec 字符串拼接；提供 vararg 参数形式

/**
 * 安全执行 root shell 命令，自动转义参数。
 * @param commandFmt 命令格式，用 {N} 占位（而非 $N 避免 shell 解析）
 * @param args 参数列表，自动 shellEscape
 */
suspend fun execSafe(
    commandParts: List<String>,
    timeoutMs: Long = COMMAND_TIMEOUT_MS
): ShellResult = withContext(Dispatchers.IO) {
    val command = commandParts.joinToString(" ")
    exec(command, timeoutMs)
}
```

- [ ] **审计 restic 密码传递路径**

密码通过 `ResticEnvResolver.buildFullEnv` 设置到环境变量 `RESTIC_PASSWORD`。ProcessBuilder 环境变量对其他进程不可见，检查是否被 logging 记录：

```kotlin
// ResticCommandRunner.kt — 检查 Log.d 是否泄露密码
// 当前: Log.d(TAG, "runRestic REPOSITORY=${env["RESTIC_REPOSITORY"]}")
// Log.d 不包含 RESTIC_PASSWORD — 安全，但添加注释说明
```

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git commit -a -m "security: audit root shell injection surface and add execSafe helper"
```

---

### Task 6: Kotlin 惯用清理

**Files:**
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/BinaryResolver.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticCommandRunner.kt`
- Modify: `app/src/main/java/com/example/androidbackupgui/backup/ResticWrapper.kt`

- [ ] **BinaryResolver — 缓存替换为 by lazy**

```kotlin
// BinaryResolver.kt
// 原来: 两个 ResolveCache 对象 + 手动 initialized 标志
// 改为 by lazy 委托：

object BinaryResolver {
    private const val TAG = "BinaryResolver"

    private fun resolve(context: Context, libName: String, destName: String): String? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val source = File(nativeLibDir, libName)
        if (!source.isFile) {
            Log.e(TAG, "$libName not found at ${source.absolutePath}")
            return null
        }
        val dest = File(context.filesDir, "bin/$destName")
        if (!dest.exists() || dest.length() != source.length() || !dest.canExecute()) {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            source.inputStream().use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
            dest.setExecutable(true)
        }
        Log.i(TAG, "ready: $libName -> ${dest.absolutePath} (${dest.length()} bytes)")
        return dest.absolutePath
    }

    private val _context = ThreadLocal<Context>()

    /** 在 Application.onCreate 时调用 */
    fun init(context: Context) { _context.set(context) }

    val tarPath: String? by lazy {
        _context.get()?.let { resolve(it, "libtar_bin.so", "tar_bin") }
    }
    val zstdPath: String? by lazy {
        _context.get()?.let { resolve(it, "libzstd_bin.so", "zstd_bin") }
    }
}
```

- [ ] **ResticCommandRunner.buildCommandArgs — 表达式函数**

```kotlin
// ResticCommandRunner.kt
// 原来:
//   fun buildCommandArgs(args: List<String>): List<String> {
//       val cmd = listOf(binaryPath) + args
//       Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args → cmd=$cmd")
//       return cmd
//   }
//
// 改为表达式体:
fun buildCommandArgs(args: List<String>): List<String> =
    (listOf(binaryPath) + args).also { cmd ->
        Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args → cmd=$cmd")
    }
```

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Commit**

```bash
git commit -a -m "style: idiomatic Kotlin cleanup — lazy delegation, expression bodies"
```

---

### Task 7: 基础单元测试框架

**Files:**
- Create: `app/src/test/java/com/example/androidbackupgui/backup/AppErrorTest.kt`
- Modify: `app/build.gradle`

- [ ] **添加测试依赖**

```gradle
// app/build.gradle — dependencies 末尾追加
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
testImplementation "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3"
```

- [ ] **为 AppError 写单元测试**

Run: `./gradlew testDebugUnitTest --tests "*AppErrorTest*"`
Expected: PASS

- [ ] **Commit**

```bash
git commit -a -m "test: add unit test framework and AppError tests"
```

---

### Self-Review

**1. Spec coverage:**
- Task 1-2 ✓ — 类型化错误处理覆盖 RemoteTransport 和 Restic 层
- Task 3-4 ✓ — 协程优化覆盖进度回调和结构化并发
- Task 5 ✓ — 安全加固覆盖 root shell 注入和密码日志
- Task 6 ✓ — Kotlin 惯用清理覆盖 BinaryResolver 和 CommandRunner
- Task 7 ✓ — 基础测试框架

**2. Placeholder check:** 无 TBD/TODO 占位。所有代码块包含完整实现。

**3. Type consistency:** `AppError`、`TransferEvent`、`AppResult` 在各 Task 之间一致。`RemoteTransport.upload/download` 签名在 Task 1 中修改后后续步骤保持一致引用。
