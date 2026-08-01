# 第三阶段 — 死代码清理审查报告

> 审查范围: android-backup-gui 项目 37 个 Kotlin 源文件
> 审查技能: ecc-refactor-cleaner（死代码、未使用导入、重复逻辑、废弃代码）
> 已知不重复: Phase 2 已报告的 @Serializable 死注解（TypeDesign F12）不在此重复
> 已知不重复: memory 中 7 个待处理项不在此重复

---

## 严重程度分级

| 等级 | 含义 |
|------|------|
| 🔴 **严重** | 功能层面死代码，占用维护成本，可能引发混淆 |
| 🟠 **中** | 未使用导入/参数，可能清理但非功能阻塞 |
| 🟡 **低** | 装饰性/可清理但不影响运行 |

---

## 🔴 严重发现

### F1. `MD4Provider.kt` 整文件死代码

**文件**: `app/src/main/java/com/example/androidbackupgui/backup/MD4Provider.kt`
**行号**: 1-137（整文件）

**问题**: `MD4Provider` 被 `MissingAlgoProvider` 完全取代。`MissingAlgoProvider` 提供了 `MD4` + `AESCMAC` 两种算法注入，且是 `SmbTransport` 实际调用的对象。`MD4Provider` 在任何地方都未被引用。

**证据**:
- `SmbTransport` 调用的是 `MissingAlgoProvider.register()`
- 全局搜索 `MD4Provider` 仅命中自身文件

**建议**: 删除整个 `MD4Provider.kt` 文件。

---

### F2. `BackupFragment.kt` 三个死方法（流式备份未接入）

**文件**: `app/src/main/java/com/example/androidbackupgui/ui/BackupFragment.kt`
**行号**: 440-546

**问题**: 以下三个方法定义了流式备份逻辑但从未被调用：

| 方法 | 行号 | 说明 |
|------|------|------|
| `estimateBackupSize()` | 440 | 估算备份数据大小 |
| `hasEnoughSpace()` | 455 | 检查磁盘空间是否充足 |
| `runStreamingResticBackup()` | 472 | 执行流式备份（FIFO 管道） |

**证据**: 全局搜索三个方法名，除自身定义外无任何调用点。`startBackup()` 方法走的是常规 restic `backup` 路径，未调用流式路径。

`runStreamingResticBackup` 上标注了 `@Suppress("UNUSED_PARAMETER")` 且参数 `outputDir: File` 从未使用，说明开发者已知此方法目前是死代码。

**建议**: 删除三个方法及相关 `import android.os.StatFs`（如果没有其他用途）。或将流式备份接入到 `startBackup` 的条件分支中。

---

### F3. `RemoteTransport.isFileNotFound()` 未使用扩展函数

**文件**: `app/src/main/java/com/example/androidbackupgui/backup/RemoteTransport.kt`
**行号**: 73-75

```kotlin
internal fun AppError.isFileNotFound(): Boolean =
    this is AppError.Remote && this.isNotFound
```

**问题**: 此扩展函数定义后从未在任何地方调用。`Remote` 错误中的 `isNotFound` 字段通过 `when (error) { is AppError.Remote -> ... }` 模式匹配访问，不需要扩展函数。

**证据**: 全局搜索 `isFileNotFound` 仅命中此定义。

**建议**: 删除此扩展函数。

---

### F4. `DataSizes` 数据类及其字段从未使用

**文件**: `app/src/main/java/com/example/androidbackupgui/backup/AppScanner.kt`
**行号**: 26-33

```kotlin
@Serializable
data class DataSizes(
    val apkBytes: Long = 0,
    val userBytes: Long = 0,
    // ...
)
```

```kotlin
data class AppInfo(
    // ...
    val dataSizes: DataSizes = DataSizes(),  // 33 行
)
```

**问题**: `DataSizes` 类型仅用于 `AppInfo.dataSizes` 字段的默认值，没有任何代码对此字段写入非默认值或读取。这是残留的"预留"字段。

**证据**: 全局搜索 `dataSizes` 仅命中定义行（33）和 `DataSizes` 类型本身（26）。`@Serializable` 注解也是死注解（`AppInfo` 从未被 kotlinx-serialization 序列化）。

**建议**: 删除 `DataSizes` 数据类和 `AppInfo.dataSizes` 字段。保留 `@Serializable` 的清理评估留给 Phase 2 已知报告。

---

## 🟠 中等发现

### F5. 子模块中 TAG 常量复制粘贴错误

**文件**: 
- `app/src/main/java/com/example/androidbackupgui/backup/ResticRepoInit.kt` 第 7 行: `private val TAG = "ResticWrapper"`
- `app/src/main/java/com/example/androidbackupgui/backup/ResticCommandRunner.kt` 第 8 行: `private val TAG = "ResticWrapper"`

**问题**: 两个子模块使用的 TAG 为 `"ResticWrapper"`，而非自己的类名。导致 logcat 中无法区分日志来源。

**建议**: 改为 `"ResticRepoInit"` 和 `"ResticCommandRunner"`。

---

### F6. 同包冗余导入（跨 7 个文件）

以下文件在 `package com.example.androidbackupgui.backup` 中，却显式 import 了同包的 `AppError`、`AppResult`、`err`：

| 文件 | 冗余导入行 |
|------|-----------|
| `ResticRepoInit.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticBackup.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticRestore.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticSnapshotOps.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticMaintenance.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticWrapper.kt` | `import com.example.androidbackupgui.backup.AppError/AppResult/err` |
| `ResticCommandRunner.kt` | `import com.example.androidbackupgui.backup.AppError`（且此导入实际未使用——该文件不引用 `AppError`）|

**建议**: 清理全部冗余 import。`ResticCommandRunner.kt` 中的 `AppError` 为真正未使用导入，应删除。

---

### F7. 真正未使用的导入

| 文件 | 行号 | 导入 | 原因 |
|------|------|------|------|
| `ResticWrapper.kt` | 5 | `import kotlinx.coroutines.isActive` | 文件内无使用 |
| `ResticWrapper.kt` | 9 | `import kotlin.coroutines.coroutineContext` | 文件内无使用 |
| `BackupFragment.kt` | 34 | `import com.example.androidbackupgui.backup.formatSize` | 文件内无使用 |
| `ConfigFragment.kt` | 19-20 | `import kotlinx.coroutines.Dispatchers` / `import kotlinx.coroutines.withContext` | Fragment 类中从未使用（全部委托给 ViewModel）|
| `ConfigViewModel.kt` | 8 | `import com.example.androidbackupgui.backup.formatSize` | 文件内无使用 |

**建议**: 删除上述导入。

---

### F8. 未使用参数（已标注 `@Suppress`）

| 文件 | 函数 | 未使用参数 | 行号 |
|------|------|-----------|------|
| `ResticRestBridge.kt` | `handleConfig()` | `headers: Map<String, String>` | 166 |
| `StreamingBackup.kt` | `launchDataProducer()` | `userId: String` | 90 |
| `BackupFragment.kt` | `runStreamingResticBackup()` | `outputDir: File` | 475 |

**问题**: 参数被显式标记为未使用。如果近期无实现计划，应直接删除参数。

**建议**:
- `handleConfig`: `headers` 可以移除（HEAD/GET/POST 都不需要它）
- `launchDataProducer`: `userId` 若留作后续多用户支持，保留但记录 TODO
- `runStreamingResticBackup`: 整个方法为死代码（见 F2），删除即可

---

## 🟡 低严重度发现

### F9. `AppScanner.getAppLabel()` 方法

**文件**: `app/src/main/java/com/example/androidbackupgui/backup/AppScanner.kt`
**行号**: 87-92

```kotlin
suspend fun getAppLabel(packageName: String): String = withContext(Dispatchers.IO) {
    val result = RootShell.exec("dumpsys package ...")
    // ...
}
```

**问题**: 此 public 方法通过 `dumpsys package` 解析应用标签。但它返回的是包名（fallback），且项目中实际使用 `resolveLabels()`（通过 `PackageManager` API）来获取标签。此方法未被任何代码调用。

**证据**: 项目中使用 `resolveLabels()` 获取标签，`getAppLabel()` 无调用者。

**建议**: 确认无用后删除。

---

### F10. 重复的 if-else bridge 模式（架构级别）

在 5 个子模块中（`ResticRepoInit`, `ResticBackup`, `ResticRestore`, `ResticSnapshotOps`, `ResticMaintenance`），每个方法都重复以下模式：

```kotlin
if (backend == "local") {
    val env = envResolver.buildLocalEnv(...)
    // run restic command
} else {
    bridgeRunner.withBridge(...) { bridgeUrl ->
        val env = envResolver.buildBridgeEnv(...)
        // run restic command
    }
}
```

**影响**: `ResticMaintenance` 中 3 个方法（prune/check/stats）结构完全一致，仅有命令参数不同。跨模块总共 ~8 次重复。

**建议**: 可提取为公共执行函数，如 `withResticEnv(backend, ...) { env -> runner.runRestic(env, ...) }`。此为架构改进建议，非阻塞。

---

### F11. `BackupFragment.estimateBackupSize` 缩进错误

**文件**: `app/src/main/java/com/example/androidbackupgui/ui/BackupFragment.kt`
**行号**: 440-449

缩进层次错误：`val pkgEsc = ...` 等行应在 `for` 循环体内但缩进级别与函数体相同：

```kotlin
for (app in apps) {
val pkgEsc = app.packageName.value.shellEscape()  // ← 缩进错误
val result = RootShell.exec(...)
```

**建议**: 修复缩进（但该函数本身是死代码 F2，删除后自然解决）。

---

### F12. 重复的 UID 解析逻辑

**文件**: 
- `AppScanner.kt` — `hasKeystore()`（行 111-117）中解析 UID 的逻辑
- `RestoreOperation.kt` — `resolveAppUid()`（行 462-490）中解析 UID 的逻辑

**问题**: 两处通过 `dumpsys package ... | grep 'userId='` 解析 UID 的代码逻辑高度相似。`RestoreOperation.resolveAppUid()` 更完整（支持 3 种 fallback），但 `AppScanner.hasKeystore()` 有独立的实现。

**建议**: 可将 UID 解析提取为公共工具函数，避免两处维护。

---

## 汇总

| 编号 | 严重度 | 类别 | 位置 | 建议 |
|------|--------|------|------|------|
| F1 | 🔴 | 死代码 | `MD4Provider.kt` 整文件 | 删除 |
| F2 | 🔴 | 死代码 | `BackupFragment.kt` 440-546（3 个方法）| 删除或接入 |
| F3 | 🔴 | 死代码 | `RemoteTransport.kt:73-75` | 删除扩展函数 |
| F4 | 🔴 | 死代码 | `AppScanner.kt:26-33` DataSizes | 删除 |
| F5 | 🟠 | 错误TAG | `ResticRepoInit.kt:7`, `ResticCommandRunner.kt:8` | 改为类名 |
| F6 | 🟠 | 冗余导入 | 7 个文件中的同包 import | 清理 |
| F7 | 🟠 | 未使用导入 | 5 个文件 | 删除 |
| F8 | 🟠 | 未使用参数 | 3 个函数（已 @Suppress）| 删除参数或加 TODO |
| F9 | 🟡 | 死代码 | `AppScanner.kt:87-92` getAppLabel | 确认后删除 |
| F10 | 🟡 | 重复模式 | 5 个子模块中的 if-else bridge | 提取公共执行函数 |
| F11 | 🟡 | 格式问题 | `BackupFragment.kt:440-449` 缩进 | 修复（随 F2 解决）|
| F12 | 🟡 | 重复逻辑 | UID 解析在两处重复 | 提取工具函数 |

---

## 清理收益估算

- 可删除文件: 1 个（`MD4Provider.kt`, ~5.1KB）
- 可删除代码行: ~150 行（死方法 + DataSizes + 扩展函数）
- 可清理导入: ~20 行（冗余 + 未使用导入）
- 可清理参数: 3 个
- 代码库缩减: ~6-8% 的源代码量
