# 静默失败审查报告 — android-backup-gui

> 审查日期: 2026-06-06
> 审查范围: 37 个 Kotlin 源文件
> 已排除: memory://root 已知的 7 个待处理项

---

## 严重程度分级

- **CRITICAL**: 数据静默损坏或丢失，用户无法感知
- **HIGH**: 错误被吞没，导致后续操作基于错误假设继续
- **MEDIUM**: 错误被吞没但影响范围有限，或仅影响辅助功能
- **LOW**: 微小错误处理缺失，实际影响小

---

## 发现清单

### F1 [HIGH] — SMB 上传大小不匹配不报告错误

**文件**: `SmbTransport.kt:103-109`
**类型**: 未检查的返回值 / 静默数据损坏

```kotlin
if (actualSize != fileSize) {
    Log.w(TAG, "upload size mismatch: local=$fileSize smb=$actualSize")
    SmbFileOutputStream(remote).use { it.write(ByteArray(0)) }
    val retrySize = freshRemote.length()
    Log.w(TAG, "upload retry: smb=$retrySize bytes")
}
// 继续返回 Success(Unit)
```

即使 SMB 端实际存储的字节数与本地不一致，`upload()` 仍返回 `AppResult.Success(Unit)`。写入零字节空数组的"修复"尝试没有验证效果。如果 SMB 服务器写入缓存有问题或磁盘空间不足，restic blob 数据可能部分损坏，而上层调用者 (`RestBridgeRunner`) 不知道。

**建议**: 当 `actualSize != fileSize` 时，应返回 `err(AppError.Remote("SMB 上传大小不匹配: local=$fileSize vs smb=$actualSize", "upload"))`。

---

### F2 [HIGH] — backupUserData 全失败时返回成功

**文件**: `BackupOperation.kt:255-257`
**类型**: 错误替换/空回退

```kotlin
if (!archiveCreated) {
    Log.w(TAG, "backupUserData: $packageName all methods failed ...")
    return true  // 返回成功！
}
```

当三种数据备份方法全部失败时（目录不存在、权限不足、tar 不可用），函数返回 `true`。上层调用者 (`BackupOperation.backupApps` line 131) 看到 `true` 就认为数据备份成功，累加 `successAtomic`，用户看到的报告就是"成功"。应用的用户数据被静默跳过。

**建议**: 改为 `return false` 让调用者知道数据备份实际失败。如果需要容错（某些应用确实没有数据目录），应在 `backupUserData` 外部判断，或返回区分"跳过"和"失败"的信号。

---

### F3 [HIGH] — CancellationException 被空 catch 吞没

**文件**: `ResticBackup.kt:55-58`, `ResticBackup.kt:73-77`, `ResticBackup.kt:117-120`, `ResticBackup.kt:130-134`
**类型**: 异步错误丢失

```kotlin
try {
    val progress = resticJson.decodeFromString<ResticWrapper.ResticProgress>(line)
    if (progress.messageType == "status") emit(progress)
} catch (_: Exception) { }
```

`catch (_: Exception)` 会捕获 `kotlinx.coroutines.CancellationException`。如果协程在 JSON 解析期间被取消，取消信号被吞没，进度回调继续运行。虽然在 `runResticStreaming`/`runResticWithStdin` 内部也有协程活跃检查（`!coroutineContext.isActive`），但取消信号仍可能延迟或丢失。

**建议**: 在空 catch 前加 `catch (e: CancellationException) { throw e }`，或改用 `catch (e: Exception) { if (e is CancellationException) throw e }`。

---

### F4 [HIGH] — WebDAV mkdirs 完全失败时仍返回成功

**文件**: `WebdavTransport.kt:153-155`
**类型**: 错误替换

```kotlin
} catch (e: Exception) {
    Log.w(TAG, "mkdirs failed: $remotePath — ${e.message}")
    AppResult.Success(Unit)  // best-effort
}
```

即使所有目录层级都无法创建，该方法返回 `AppResult.Success(Unit)`。注释说"upload will fail if dir can't be created"，但上传可能在更深层的操作上以不同的错误信息失败（如"permission denied" vs "directory not found"），使诊断更加困难。上层调用者无法区分"目录已存在"和"完全无法创建"。

**建议**: 仅在确定目录确实存在时返回 Success（如 SMB 实现中检测 `STATUS_OBJECT_NAME_COLLISION`）。对所有其他异常应返回 `err(AppError.Remote(...))`。

---

### F5 [MEDIUM] — WifiManager.restore 始终返回 true

**文件**: `WifiManager.kt:54-85`
**类型**: 错误替换

整个 `restore()` 方法始终返回 `true`（line 84），即使：
- `findWifiConfigPath()` 返回 null 且 fallback 路径无法创建目录（line 63-64 返回 false，但被统一 return@withContext false 处理... 等等这里 line 84 是最后一行）
  - 实际上 line 63 `return@withContext false` 确实会提前返回。但如果成功执行到 line 84，无论如何都返回 true。中间 `cp`、`chown`、`chmod` 的失败仅被记录日志，不通知调用者。

**建议**: `cp` 或 `chmod`/`chown` 失败时应返回 false。当前 RestoreFragment 中 `WifiManager.restore(dir)` 的返回值没有被使用，但接口应该诚实。

---

### F6 [MEDIUM] — ResticWrapper.getLatestSnapshotAppDetails 静默返回 null

**文件**: `ResticWrapper.kt:270-275`, `ResticWrapper.kt:288`
**类型**: 空回退

```kotlin
is AppResult.Failure -> {
    Log.w(TAG, "getLatestSnapshotAppDetails: listSnapshots failed: ...")
    null
}
// 和
is AppResult.Failure -> return@withContext null
```

当 `listSnapshots()` 或 `dump()` 失败时返回 `null`。调用者 (`BackupFragment.kt:228`) 仅检查 `snapshotApps != null`，看到 null 就跳过累积快照逻辑。用户不知道仓库存在但无法读取——也可能是仓库密码错误、网络问题或权限问题。但此行为在 API 文档中有意说明，且后续备份仍能工作，只是失去了累积合并能力。

**建议**: 考虑返回 `AppResult<Map<String, SnapshotAppInfo>?>` 以区分"无快照"和"读取失败"。或者增加 UI 通知。

---

### F7 [MEDIUM] — parseAppDetailsJson 捕获所有异常

**文件**: `ResticWrapper.kt:315-317`
**类型**: 被吞没的异常

```kotlin
} catch (_: Exception) {
    Log.w(TAG, "parseAppDetailsJson: failed to parse JSON")
}
```

捕获所有 `Exception` 类型（包括 `CancellationException`、`OutOfMemoryError` 等）。虽然当前函数在 `Dispatchers.IO` 上下文外的同步路径调用，但应该缩小异常范围。

**建议**: 改为 `catch (e: org.json.JSONException)`。

---

### F8 [MEDIUM] — StreamingBackup mkfifo 失败不报告

**文件**: `StreamingBackup.kt:50`
**类型**: 未检查的返回值

```kotlin
RootShell.exec("mkfifo '${fifo.absolutePath.shellEscape()}'")
```

`mkfifo` 的执行结果完全被忽略。如果 `mkfifo` 失败（例如文件系统只读、磁盘满），FIFO 文件不存在，后续 `restic backup --stdin` 会以模糊的错误失败。`StreamingBackup.prepareStreaming` 返回的 `StreamingResult` 将包含无效的 FIFO 路径。调用者在 `BackupFragment.kt:484` 直接使用结果，没有验证 FIFO 是否创建成功。

**建议**: 检查结果并抛出异常或返回失败信号。

---

### F9 [MEDIUM] — BackupFragment 中 restore 操作结果被忽略

**文件**: `ui/BackupFragment.kt:274-284`
**类型**: 未检查的返回值

```kotlin
ResticWrapper.restore(
    repoPath = config.resticRepo,
    password = config.resticPassword,
    snapshotId = latestSnap.shortId,
    targetPath = backupRoot.absolutePath,
    ...
)
```

在累积备份流程中，从 restic 仓库恢复最新快照到本地暂存目录的结果完全被忽略。如果恢复失败（例如密码错误、网络中断），`backupRoot` 目录可能不完整，但备份操作继续执行。后续 `BackupOperation.backupApps` 可能会基于不完整的文件结构工作。

**建议**: 检查 `restore()` 的 `AppResult`，如果失败则终止备份流程并通知用户。

---

### F10 [MEDIUM] — StreamingBackup.launchDataProducer 的 tar 失败仅记录日志

**文件**: `StreamingBackup.kt:116-118`
**类型**: 被吞没的异常

```kotlin
if (!result.isSuccess) {
    Log.w(TAG, "Data backup failed for $pkgName: ${result.error}")
}
```

单个应用的 tar 数据备份失败时仅记录日志，继续下一个应用。调用者 (`BackupFragment.kt:534`) 通过 `producerJob.await()` 等待完成，但该函数总是返回 `true`（除非协程被取消）。这意味着即使某些应用的数据完全没有备份，调用者也认为一切正常。restic 对缺失数据无法感知——它只归档了 FIFO 中收到的内容。

**建议**: 收集失败列表并通过返回值或回调通知调用者。

---

### F11 [MEDIUM] — RestBridgeRunner 中未识别的后端静默穿透

**文件**: `RestBridgeRunner.kt:61`
**类型**: 空回退

```kotlin
val t = transportFactory(...)
    ?: return block(repoPath)
```

当 `RemoteTransport.create()` 返回 `null`（未知 backend），代码直接调用 `block(repoPath)`，其中 `repoPath` 是原始路径字符串而不是桥接 URL。restic 会收到一个可能无效的仓库 URL，产生令人困惑的错误（"repository doesn't exist" 而不是"未知后端类型"）。

**建议**: 至少记录一个错误，或抛出异常说明后端类型不支持。

---

### F12 [MEDIUM] — SMB listFiles 在无权限时静默返回空列表

**文件**: `SmbTransport.kt:165-186`
**类型**: 空回退

```kotlin
val entries = dir.listFiles()
    ?.map { f -> ... }
    ?: emptyList()
```

`SmbFile.listFiles()` 在 SMB 权限不足时可能返回 `null`。此时 `?: emptyList()` 将静默返回空列表。调用者可能认为路径是空的，而不是没有读取权限。SMB 协议可以在 `SmbException` 中返回具体的 ntStatus 错误，但这里的 null 合并将错误掩盖了。

**建议**: 在 `else` 分支或 `catch` 中检查文件是否确实存在，如果存在但 listFiles 返回 null，应返回错误而非空列表。

---

### F13 [MEDIUM] — backupPermissions 静默跳过

**文件**: `BackupOperation.kt:349-354`
**类型**: 被吞没的异常

```kotlin
private suspend fun backupPermissions(packageName: String, appDir: File) {
    val result = RootShell.exec("dumpsys package ...")
    if (result.output.isNotBlank()) {
        File(appDir, "permissions.txt").writeText(result.output)
    }
}
```

如果 `dumpsys package` 命令失败或输出为空，权限备份静默跳过。`backupApps` 在 line 163 调用此函数时不检查结果，也不记录错误。恢复时将没有权限文件，应用以默认权限运行。

**建议**: 至少在 `dumpsys` 命令失败时记录日志。考虑返回 `Boolean` 让调用者知晓。

---

### F14 [MEDIUM] — backupSsaid 静默跳过

**文件**: `BackupOperation.kt:331-347`
**类型**: 被吞没的异常

```kotlin
private suspend fun backupSsaid(packageName: String, appDir: File, userId: String) {
    val result = RootShell.exec("cat '$ssaidFile' 2>/dev/null")
    if (!result.isSuccess || result.output.isBlank()) return
    // ...
}
```

如果 XML 文件无法读取或解析失败，SSAID 备份完全静默跳过。SSAID 是 Google 广告标识符，丢失后用户可能收到新的 ID。

**建议**: 在 cat 命令失败时记录警告日志。

---

### F15 [MEDIUM] — initResticRepo 使用 exceptionOrNull 可能导致 null 显示

**文件**: `ui/ConfigViewModel.kt:205-207`
**类型**: 错误替换

```kotlin
Log.e(TAG, "initResticRepo failed: ${result.exceptionOrNull()?.message}")
_uiState.update { it.copy(resticStatus = it.resticStatus.copy(
    message = "初始化失败: ${result.exceptionOrNull()?.message}"
))}
```

`AppResult.exceptionOrNull()` 创建一个新的 `RuntimeException`，如果原始 `AppError.message` 为 null（例如 `AppError.Restic("", -1, "")`），用户将看到 "初始化失败: null"。

**建议**: 使用 `${result.errorOrNull()?.message ?: "未知错误"}`。

---

### F16 [MEDIUM] — RestBridgeRunner 中临时文件删除结果未检查

**文件**: `RestBridgeRunner.kt:85-88`
**类型**: 资源泄露

```kotlin
val blobs = cacheDir.listFiles { f -> f.name.startsWith("restic_blob_") }
if (blobs != null) {
    for (f in blobs) f.delete()
}
```

临时 blob 文件删除的结果未检查，且 `listFiles` 筛选器可能遗漏子目录中的临时文件（如 `ResticRestBridge` 在 `cacheDir` 中创建 `restic_blob_*` 文件）。随着操作频繁进行，可能累积未清理的临时文件。

**建议**: 使用 `f.delete()` 的返回值进行日志记录，并考虑递归清理。

---

### F17 [LOW] — RootShell.ensureSession 静默返回 false

**文件**: `root/RootShell.kt:63-67`
**类型**: 被吞没的异常

```kotlin
suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
    try {
        Shell.getShell().isRoot
    } catch (_: Exception) { false }
}
```

如果 `Shell.getShell()` 抛出任何异常（包括 `NullPointerException`、`RuntimeException`），静默返回 `false`。调用者无法区分"没有 root 权限"和"libsu 未初始化或其他错误"。

**建议**: 记录异常。可以考虑区分不同类型的失败。

---

### F18 [LOW] — AppScanner 多项查询静默失败

**文件**: `AppScanner.kt:41,53,96`
**类型**: 空回退

```kotlin
if (!result.isSuccess) return@withContext emptyList()
```

`scanThirdParty`、`scanSystem`、`getApkPaths` 在 shell 命令失败时返回空列表。如果 `pm list packages` 因为 root 权限临时问题失败，用户看到的应用列表为空，但没有任何错误提示。

**建议**: 在 UI 层调用前检查返回的空列表并显示适当消息（已在 `BackupFragment.scanApps()` 中捕获异常，但 shell 层面的失败可能被漏过）。

---

### F19 [LOW] — backupUserData tar 命令可能静默失败

**文件**: `BackupOperation.kt:228`
**类型**: 未检查的返回值

```kotlin
val dirs = dataPaths.filter { RootShell.exec("test -d '${it.shellEscape()}'").isSuccess }.toMutableList()
```

`test -d` 在 nsenter namespace 切换后可能对某些路径返回假阴性。如果所有 `test -d` 都失败，`dirs` 为空列表，代码会转到 `else` 分支（line 234-238）尝试直接运行 tar，而 tar 也会因为没有源路径而静默失败或产生空归档。此时 `archiveCreated` 保持 false，进入 line 255 的 fallback 处理——但这个 fallback 返回 true（见 F2）。

**建议**: 如果 `dirs` 为空且 tar 直接执行也未产生输出，应返回明确的失败信号。

---

### F20 [LOW] — WifiManager.backup 结果未在 BackupOperation 中检查

**文件**: `ui/BackupFragment.kt:310`
**类型**: 未检查的返回值

```kotlin
WifiManager.backup(File(result.outputDir))
```

WiFi 配置备份的结果完全被忽略。如果 WiFi 备份失败，用户不会收到任何通知。`WifiManager.backup()` 可以返回 `null`（失败时），但调用者没有使用返回值。

**建议**: 至少记录结果，考虑在最终摘要中显示 WiFi 备份状态。

---

### F21 [LOW] — estimateBackupSize 忽略 du 错误

**文件**: `ui/BackupFragment.kt:440-449`
**类型**: 未检查的返回值

```kotlin
val result = RootShell.exec("du -sb /data/data/$pkgEsc 2>/dev/null | cut -f1")
val size = result.output.trim().toLongOrNull() ?: 0L
```

如果 `du` 命令失败、输出为空或解析失败，该应用的估计大小为 0。最终的空间估算可能严重偏低（仅用于判断是否需要流式备份），可能导致本应触发流式备份的大数据集使用暂存模式。

**建议**: 考虑使用保守的默认值或根据应用大小粗略估算。

---

### F22 [LOW] — BackupOperation 中 chmod 结果未检查

**文件**: `BackupOperation.kt:173`
**类型**: 未检查的返回值

```kotlin
RootShell.exec("chmod -R 0755 '${backupRoot.absolutePath}'")
```

备份完成后设置目录权限的结果未检查。虽然不影响备份数据的完整性，但如果 `chmod` 失败，后续读取备份的用户可能会遇到权限问题。

**建议**: 至少记录 `chmod` 失败日志。

---

### F23 [LOW] — BackupFragment 中前台服务启动异常被吞没

**文件**: `ui/BackupFragment.kt:193-195`
**类型**: 被吞没的异常

```kotlin
try {
    ContextCompat.startForegroundService(requireContext(), serviceIntent)
} catch (_: Exception) {}
```

如果前台服务启动失败（例如缺少权限、应用在后台），异常被完全吞没。备份操作仍然继续，但进程可能被 Android 杀死。

**建议**: 记录异常，考虑通知用户服务启动失败。

---

### F24 [LOW] — ConfigFragment 中 OperationEvent 的 InitFailed/PruneFailed 不显示错误详情

**文件**: `ui/ConfigFragment.kt:157-159`
**类型**: 错误替换

```kotlin
is OperationEvent.InitFailed -> {
    Log.d(TAG, "init failed")
    Snackbar.make(binding.root, "仓库初始化失败", Snackbar.LENGTH_SHORT).show()
}
```

InitFailed/PruneFailed 事件不携带错误详情，用户只看到"初始化失败"/"清理失败"，不知道具体原因。实际错误消息在 ViewModel 的 `resticStatus.message` 中已经设置，但 UI 没有在 snackbar 中使用它。

**建议**: 从 ViewModel 状态读取错误详情并在 snackbar 中显示，或让 OperationEvent 携带错误消息。

---

### F25 [LOW] — RestBridgeRunner 中缓存传输不被清理

**文件**: `RestBridgeRunner.kt:58-63`
**类型**: 资源泄露

```kotlin
if (cachedTransportKey != key) {
    cachedTransport?.let { Log.d(TAG, "discarding stale cached transport") }
    val t = transportFactory(...)
    ...
    cachedTransport = t
    cachedTransportKey = key
}
```

当缓存键变化时，旧的 `cachedTransport`（SMB 会话或 WebDAV client）被直接丢弃而不关闭。对于 `SmbTransport`，内部的 `CIFSContext` 和 jcifs-ng 连接可能保持打开，直到 GC 触发 finalizer。对于 `WebdavTransport`，OkHttp 客户端可能保持连接池和线程。

**建议**: 如果 `RemoteTransport` 接口添加 `close()` 方法，在替换缓存时调用。

---

## 分类统计

| 严重程度 | 数量 | 关键文件 |
|---|---|---|
| HIGH | 4 | SmbTransport, BackupOperation, ResticBackup, WebdavTransport |
| MEDIUM | 12 | ResticWrapper(2), StreamingBackup(2), BackupFragment, WifiManager, RestBridgeRunner, SmbTransport, BackupOperation(2), ConfigViewModel, AppScanner |
| LOW | 9 | RootShell, AppScanner(3), BackupFragment(3), BackupOperation, ConfigFragment, RestBridgeRunner |

**发现总数**: 25

---

## 总结与优先修复建议

### 必须修复 (HIGH)
1. **F1** (`SmbTransport.kt:103-109`) — SMB 上传后大小校验失败应返回错误，而非静默继续
2. **F2** (`BackupOperation.kt:255-257`) — `backupUserData` 全方式失败时返回 `true` 是在告知上层"数据已备份"
3. **F3** (`ResticBackup.kt:55-58` 等) — 进度回调中的空 catch 吞没 `CancellationException`，需添加重新抛出
4. **F4** (`WebdavTransport.kt:153-155`) — `mkdirs` 完全失败返回 Success 是错误替换

### 高优先级 (MEDIUM)
- **F10** — `StreamingBackup.launchDataProducer` 不传播 tar 错误
- **F12** — `SmbTransport` listFiles 返回 null 时可能是权限问题
- **F13/F14** — `backupPermissions`/`backupSsaid` 静默跳过
- **F8** — `StreamingBackup.mkfifo` 结果未检查

### 建议
整个代码库中使用 `catch (_: Exception)` 的模式需要系统性审查：应在所有协程 lambda 中的空 catch 前加 `catch (e: CancellationException) { throw e }`。关键入口点（如 `ResticBackup.kt:58`）已有 `CancellationException` 被吞没的问题。
