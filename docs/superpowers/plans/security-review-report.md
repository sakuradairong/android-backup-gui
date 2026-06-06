# Android Backup GUI — OWASP 导向安全审查报告

> 审查日期: 2026-06-06
> 范围: 全部 37 个 Kotlin 源文件 + AndroidManifest.xml
> 已知问题已排除（memory 中记录的 7 项 Remaining Gaps 不在此报告重复）

---

## 目录
1. [认证与授权](#1-认证与授权)
2. [输入校验](#2-输入校验)
3. [敏感数据处理](#3-敏感数据处理)
4. [API 安全](#4-api-安全)
5. [安全配置](#5-安全配置)
6. [日志/调试信息泄露](#6-日志调试信息泄露)
7. [Intent/组件暴露](#7-intent组件暴露)

---

## 1. 认证与授权

### 1.1 无权限检查直接执行 Root 命令

**严重程度**: 中

**文件**: `backup/BackupOperation.kt`
**位置**: 第 109、173、228、246-249、273-276、297-300、308-311、334、350、369 行等

`RootShell.exec()` 在整个代码库中被广泛调用，但在调用前不做任何权限检查。虽然没有运行时安全检查（因为是 root 应用），但以下操作直接通过 `RootShell.exec()` 执行系统命令并拼接用户控制的输入：

```kotlin
// BackupOperation.kt:109  — cp 命令使用 shellEscape
RootShell.exec("cp '${apkPath.shellEscape()}' ...")
// BackupOperation.kt:297  — tar 命令拼接目录名
RootShell.exec("set -o pipefail; $tarCmd -cf - $excludeArgs ${dirs.joinToString(" ") { "'${it.shellEscape()}'" }} ...")
// BackupOperation.kt:333  — 读取含有应用名的系统文件
val result = RootShell.exec("cat '$ssaidFile' 2>/dev/null")
```

**修复建议**: 虽然 `shellEscape()` 提供了防御，但所有 root shell 调用应使用 `execSafe()` 而不是 `exec()`。

### 1.2 RootShell 启用 libsu 详细日志

**严重程度**: 低

**文件**: `root/RootShell.kt`
**位置**: 第 54 行

```kotlin
Shell.enableVerboseLogging = true
```

生产环境中启用 libsu 的详细日志，会将所有 su 会话操作的细节写入 logcat。

**修复建议**: 改为构建标志控制，仅在 debug 构建启用。

### 1.3 QUERY_ALL_PACKAGES 敏感权限

**严重程度**: 低（已声明为必要）

**文件**: `app/src/main/AndroidManifest.xml`
**位置**: 第 7 行

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

Google Play 对 `QUERY_ALL_PACKAGES` 有严格审核要求，该应用的核心功能需要此权限以列举用户安装的应用。

**修复建议**: 确认应用不上架 Google Play 或已通过审核。当前无修复必要。

---

## 2. 输入校验

### 2.1 Restic 密码为空时仍继续执行

**严重程度**: 高

**文件**: `ui/ConfigViewModel.kt`
**位置**: 第 180-183 行

```kotlin
if (form.repo.isEmpty() || form.password.isEmpty()) {
    _uiState.update { it.copy(resticStatus = it.resticStatus.copy(message = "请填写仓库路径和密码")) }
    return
}
```

`initResticRepo()` 在 `form.password.isEmpty()` 时返回。但 `refreshResticStatus()`（第 217-256 行）和 `showResticStats()`（第 258-295 行）和 `pruneResticSnapshots()`（第 297-344 行）在 `form.password` 为空时不会检查，直接将空密码传给 `ResticWrapper`。

**修复建议**: 在所有操作入口添加密码空值检查，或至少记录 warning。

### 2.2 用户配置字段无输入校验

**严重程度**: 中

**文件**: `backup/BackupConfig.kt`
**位置**: 第 78-136 行 (`fromFile`)

配置解析使用 `toIntOrNull()` 处理整数（静默回退到默认值），字符串字段没有任何长度、格式或内容验证。例如：
- `resticBackendUrl` 不验证是否为合法 URL
- `resticBackendShare` 不验证 SMB share 名称格式
- `resticBackendUser` 和 `resticBackendPass` 不验证为空时的行为

**文件**: `ui/ConfigFragment.kt`
**位置**: 第 200-217 行 (`saveConfig`)

```kotlin
resticPassword = binding.resticPasswordEdit.text?.toString() ?: "",
resticBackendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: "",
```

来自 UI 的输入仅进行了简单的 null→empty 转换，没有任何格式校验。

**修复建议**: 添加输入验证层，至少检查 URL 格式、必填字段非空。对于 restic 仓库密码，提示用户确认。

### 2.3 ResticRestBridge URI 路径注入风险

**严重程度**: 中

**文件**: `backup/ResticRestBridge.kt`
**位置**: 第 62-117 行 (`handleRequest`)

URI 路径解析时，`segments` 由 `strippedPath.split("/").filter { it.isNotEmpty() }` 产生，然后直接用于构建远程路径：

```kotlin
// 第 100-102 行
val type = firstSegment
val name = if (segments.size >= 2) segments.drop(1).joinToString("/") else null
```

以及后续的远程路径构建：
```kotlin
// 第 232 行
val remoteDir = "$remoteBase/$type"
// 第 262 行
val remotePath = "$remoteBase/$type/$name"
```

虽然 restic 是唯一客户端，但 URI 中的编码路径可能被滥用于路径遍历。`name` 通过 `joinToString("/")` 直接拼接到远程路径。

**修复建议**: 对 `type` 和 `name` 进行路径字符过滤，拒绝 `..`、`./` 等特殊路径序列。添加到 `RemoteTransport` 调用前。

---

## 3. 敏感数据处理

### 3.1 Restic 密码和凭据明文存储

**严重程度**: 高

**文件**: `backup/BackupConfig.kt`
**位置**: 第 69、73 行

```kotlin
val resticPassword: String = "",
val resticBackendPass: String = "",
```

**文件**: `backup/BackupConfig.kt`
**位置**: 第 139-186 行 (`toFile`)

```kotlin
appendLine("restic_password=\"${config.resticPassword}\"")
appendLine("restic_backend_pass=\"${config.resticBackendPass}\"")
```

所有密码以明文写入配置文件 `backup_settings.conf`，存储在 `filesDir`（`/data/data/com.example.androidbackupgui/files/`）。在已有 root 权限的设备上，其他 root 进程可以读取该文件。Android `android:allowBackup="true"` 更使 ADB 备份可以提取此文件。

**修复建议**: 
- 使用 `EncryptedSharedPreferences`（AndroidX Security）加密存储密码
- 或在运行时从用户输入获取密码，不持久化到磁盘
- 将 `allowBackup` 设为 `false` 以防止 ADB 备份提取

### 3.2 SSAID 唯一标识符泄露

**严重程度**: 中

**文件**: `backup/BackupOperation.kt`
**位置**: 第 331-347 行 (`backupSsaid`)

```kotlin
val ssaidLine = result.output.lines().firstOrNull { line ->
    line.contains("packageName=\"$packageName\"") || line.contains("packageName='$packageName'")
}
val value = ssaidLine
    ?.substringAfter("value=\"")
    ?.substringBefore("\"")
    ?.takeIf { it.isNotBlank() }
if (value != null) {
    File(appDir, "ssaid.txt").writeText(value)  // 明文写入备份输出
}
```

SSAID（Settings Secure Android ID）是每个应用的唯一标识符，属于 `Settings.Secure` 级别的敏感标识符。备份文件中的 `ssaid.txt` 以明文存储，且：

**文件**: `backup/LogUtil.kt` 间接受到影响（日志中可能包含 SSAID）

实际上没有日志泄露，但 `ssaid.txt` 作为备份的一部分进入 restic 仓库，restic 仓库本身加密但元数据路径可见。

**修复建议**: SSAID 备份/恢复是 restore 功能的核心需求，当前处理方式可接受。但应在文档中说明此行为。

### 3.3 WiFi 配置包含网络密码

**严重程度**: 中

**文件**: `backup/WifiManager.kt`
**位置**: 第 41-47 行 (`backup`)

```kotlin
val result = RootShell.exec("cp '$wifiSource' '${wifiDest.absolutePath.shellEscape()}'")
```

WiFi 配置文件（`WifiConfigStore.xml`、`wpa_supplicant.conf`）包含网络 SSID 和密码的明文或哈希值。这些文件被复制到备份输出，进而可能被 restic 快照处理。

**修复建议**: 在备份 WiFi 配置时过滤或加密敏感字段。WiFi 密码至少应标记为需要额外保护。

### 3.4 Restic 密码通过环境变量传递

**严重程度**: 中性（设计合理）

**文件**: `backup/ResticEnvResolver.kt`
**位置**: 第 17、35 行

```kotlin
env["RESTIC_PASSWORD"] = password
```

通过环境变量而非命令行参数传递密码是**正确的做法**，可以防止密码被 `ps` 等进程列表工具窥探。这是值得保持的好设计。

**注意**: 环境变量仍可被 `/proc/self/environ` 读取（在 root 权限下），但对于该应用的威胁模型（已有 root 权限），这是可接受的。

---

## 4. API 安全

### 4.1 ResticRestBridge 无认证监听本地端口

**严重程度**: 高

**文件**: `backup/ResticRestBridge.kt`
**位置**: 第 22-27 行

```kotlin
class ResticRestBridge(...) : NanoHTTPD(0) {
```

`NanoHTTPD(0)` 默认绑定到 `0.0.0.0`（所有网络接口），端口由系统分配（0 表示任意可用端口）。桥接器不包含任何认证机制：

- 第 36-54 行 (`serve`): 没有 IP 过滤、Token 检查或任何认证
- 第 62-117 行 (`handleRequest`): 直接处理所有 HTTP 方法（GET/POST/DELETE/HEAD）
- 第 348-371 行 (`handlePostBlob`): 接受任意文件上传到远程存储
- 第 376-386 行 (`handleDeleteBlob`): 允许删除远程存储中的任意 blob

**文件**: `backup/RestBridgeRunner.kt`
**位置**: 第 76 行

```kotlin
val bridgeUrl = "rest:http://127.0.0.1:$port/$repoPath"
```

虽然 restic 客户端被指示连接到 `127.0.0.1`，但 NanoHTTPD 服务器绑定在 `0.0.0.0`。同一局域网/WLAN 下的其他设备可以访问此端口。

**修复建议**: 创建 NanoHTTPD 时指定只监听 127.0.0.1。NanoHTTPD 构造函数的端口参数后可以添加 IP 地址参数，或使用 `NanoHTTPD("127.0.0.1", 0)`（如果 API 支持）。否则，在启动后添加 iptables 规则限制本地访问。

### 4.2 ResticRestBridge 错误信息泄露

**严重程度**: 低

**文件**: `backup/ResticRestBridge.kt`
**位置**: 第 47-51 行

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "request failed: $method $uri", e)
    newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR, "text/plain",
        e.message ?: "Internal error"
    )
}
```

异常消息直接返回给 HTTP 客户端。更严重的是，`streamBodyToFile` 的失败也返回给客户端：

```kotlin
// 第 207-210 行
if (tmpResult.isFailure) return@runBlocking newFixedLengthResponse(
    Response.Status.INTERNAL_ERROR, "text/plain",
    "body read failed: ${tmpResult.exceptionOrNull()?.message ?: "unknown"}"
)
```

**修复建议**: 将详细的错误消息仅记录到日志，返回通用的 "Internal error"。

---

## 5. 安全配置

### 5.1 allowBackup 启用

**严重程度**: 高

**文件**: `app/src/main/AndroidManifest.xml`
**位置**: 第 13 行

```xml
android:allowBackup="true"
```

`allowBackup="true"` 允许通过 `adb backup` 提取应用的全部私有数据，包括 `filesDir` 中的 `backup_settings.conf`（包含明文 restic 密码和备份凭据）。

**修复建议**: 设置为 `false`：

```xml
android:allowBackup="false"
android:fullBackupContent="false"
```

### 5.2 无网络安全配置

**严重程度**: 中

**文件**: `app/src/main/AndroidManifest.xml`
**位置**: 第 12-18 行

应用声明了 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限，支持 WebDAV、SMB 和 rest-server 远程传输，但未配置 `android:networkSecurityConfig`。这意味着默认允许所有未加密的明文流量（HTTP），对于传输备份数据的场景存在安全风险。

**修复建议**: 添加 `res/xml/network_security_config.xml` 网络安全配置，明确允许/限制明文流量目标。如果仅使用内网 NAS，可以限制明文到特定内网网段。

### 5.3 无备份数据加密说明

**严重程度**: 低

备份的数据（应用 APK、数据目录、WiFi 配置等）不进行应用层加密。restic 仓库会进行传输中和静态加密（如果配置了），但本地 staging 目录中的备份文件放在外部存储的明文目录中。

**修复建议**: 建议用户在文档中了解：本地备份目录中的文件未加密；restic 仓库提供加密但需正确保管密码。

---

## 6. 日志/调试信息泄露

### 6.1 RootShell 命令日志泄露

**严重程度**: 中

**文件**: `root/RootShell.kt`
**位置**: 第 82、85 行

```kotlin
Log.w(TAG, "exec timeout (${timeoutMs}ms): $command")
Log.e(TAG, "exec failed: $command", e)
```

`RootShell.exec()` 在命令失败或超时时将完整的命令字符串记录到 logcat。如果 `exec()` 被传入包含密码或 token 的命令，这些敏感数据会被泄露到 logcat。

当前实现中 `BackupOperation.kt` 主要使用 `execSafe()`（通过 `shellEscape()`），但 `exec()` 是公有函数，任何调用者都可能传入未脱敏的命令。

**修复建议**: 
- 在日志中截断或脱敏命令字符串
- 或更严格地——不在日志中包含命令内容，只记录标签和错误码

### 6.2 SSAID 值记录到日志

**严重程度**: 高

**文件**: `backup/BackupOperation.kt`
**位置**: 第 345 行

```kotlin
Log.d(TAG, "backupSsaid: backed up SSAID for $packageName = $value")
```

SSAID（Settings Secure Android ID）是每个应用唯一的设备级标识符，直接以明文记录到 logcat。logcat 在 Android 8+ 受权限保护，但仍可被系统应用和 adb 读取。

**文件**: `backup/RestoreOperation.kt`
**位置**: 第 398、401、411 行

```kotlin
Log.i(TAG, "restoreSsaid: restored SSAID for $packageName via XML (uid=$uid)")
Log.w(TAG, "restoreSsaid: XML edit completed but entry not found, falling back")
Log.e(TAG, "restoreSsaid: failed to set SSAID for $packageName: ${result.error}")
```

虽然恢复端未直接记录 SSAID 值，但记录了 UID（唯一整数标识符），结合包名可识别设备。

**修复建议**: 不在日志中记录 SSAID 值，只记录操作状态。

### 6.3 LogUtil 日志文件可能包含敏感信息

**严重程度**: 中

**文件**: `backup/LogUtil.kt`
**位置**: 第 45-58 行 (`writeLog`)

```kotlin
private fun writeLog(level: String, tag: String, message: String) {
    val dir = baseDir ?: return
    executor.execute {
        ...
        val line = "$timestamp $level/$tag: $message\n"
        logFile.appendText(line)
    }
}
```

`LogUtil` 将所有 `i/w/e` 日志写入 `baseDir/logs/` 目录下的日期文件。这些日志文件包含 `LogUtil.i/w/e()` 调用的全部消息，可能包括命令参数、错误详情等敏感信息。日志文件保留 7 天。

```kotlin
// 第 77-84 行
fun getLogFiles(): List<File> {
    val logDir = File(dir, "logs")
    return logDir.listFiles()
        ?.filter { it.name.endsWith(".log") }
        ?.sortedBy { it.name } ?: emptyList()
}
```

日志文件可通过 `getLogFiles()` 获取，虽然当前没有代码直接暴露给其他应用，但 restic 备份会扫描此目录，导致日志被包含在备份快照中。

**修复建议**: 
- 添加日志级别过滤，不在文件日志中包含 `Log.d` 级别的调试信息
- 考虑在日志过虑器中脱敏已知的敏感模式（密码、SSAID、token）
- 将日志目录添加到 restic 备份排除列表

### 6.4 配置 URL 日志可能包含内嵌凭据

**严重程度**: 低

**文件**: `ui/ConfigViewModel.kt`
**位置**: 第 178 行

```kotlin
Log.i(TAG, "initResticRepo: repo=${form.repo} backend=${form.backend} url=${form.backendUrl}")
```

如果用户将凭据嵌入 backend URL（如 `https://user:password@host/path`），这些凭据会被记录到日志。WebDAV URL 有时包含用户名。

**修复建议**: 在日志中脱敏 URL 中的用户信息部分。

### 6.5 Shell 命令冗余日志

**严重程度**: 低

**文件**: `backup/ResticCommandRunner.kt`
**位置**: 第 36、42、76-77 行等

```kotlin
Log.d(TAG, "buildCommandArgs: binaryPath=$binaryPath args=$args -> cmd=$cmd")
Log.i(TAG, "runRestic cmd=${cmdArgs.joinToString(" ")}")
Log.i(TAG, "runRestic exitCode=$exitCode stdout_len=${stdout.length}")
if (stderrText.isNotEmpty()) Log.w(TAG, "runRestic stderr: ${stderrText.trim()}")
```

尽管密码通过环境变量而非命令行参数传递（正确做法），但命令参数被完整记录。在 restic `init`、`backup`、`restore` 等命令中，命令行包含仓库路径、标签、主机名等信息，这些信息本身通常不敏感，但 `args` 参数在日志中可见。

文件路径日志（第 173 行）：
```kotlin
Log.i(TAG, "runResticWithStdin cmd=${cmdArgs.joinToString(" ")} stdin=${stdinFile.absolutePath}")
```

**修复建议**: 当前日志设计合理——密码不在命令行中，因此日志不包含密码。无需更改。

---

## 7. Intent/组件暴露

### 7.1 ResticRestBridge 绑定到 0.0.0.0

**严重程度**: 高

（已在 4.1 中详述——此问题跨类别）

**文件**: `backup/ResticRestBridge.kt`
**位置**: 第 27 行

```kotlin
) : NanoHTTPD(0) {
```

NanoHTTPD 默认绑定所有网络接口。同一设备上或同一网络中的恶意应用/用户可访问此 REST 接口，读取/写入远程存储中的 blob 数据。

### 7.2 BackupService 未导出但使用隐式 Intent

**严重程度**: 低

**文件**: `backup/BackupService.kt`
**位置**: 第 21-23 行

```kotlin
const val ACTION_START_BACKUP = "com.example.androidbackupgui.action.START_BACKUP"
const val ACTION_STOP_BACKUP = "com.example.androidbackupgui.action.STOP_BACKUP"
const val EXTRA_STATUS_TEXT = "status_text"
```

**文件**: `ui/BackupFragment.kt`
**位置**: 第 190-192、391-394 行

```kotlin
val serviceIntent = Intent(requireContext(), BackupService::class.java)
serviceIntent.action = BackupService.ACTION_START_BACKUP
```

Service 声明为 `exported="false"`，所以只有同一应用内可访问——安全。Action 字符串使用完整包名前缀，避免了与其他应用的 Intent 冲突。

### 7.3 MainActivity 导出为 LAUNCHER

**严重程度**: 低（标准做法）

**文件**: `app/src/main/AndroidManifest.xml`
**位置**: 第 20-27 行

```xml
<activity android:name=".MainActivity"
    android:exported="true"
    android:windowSoftInputMode="adjustResize">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

标准 LAUNCHER Activity 导出设置，但在 root 应用上下文中，其他应用可以调用此 Activity 触发初始化和权限请求流程。

**修复建议**: 对于意外启动，可添加 `android:exported="true"` 但仅保留 MAIN/LAUNCHER intent-filter。当前配置已正确。

---

## 问题严重程度汇总

|编号|严重程度|类型|文件|行号|
|---|---|---|---|---|
|3.1|**高**|敏感数据-明文密码|BackupConfig.kt|69,73,178-182|
|5.1|**高**|安全配置-allowBackup|AndroidManifest.xml|13|
|4.1 / 7.1|**高**|API 安全-无认证桥接|ResticRestBridge.kt|27,36-54|
|6.2|**高**|日志泄露-SSAID|BackupOperation.kt|345|
|2.1|中|输入校验-密码空值检查缺失|ConfigViewModel.kt|217-256|
|2.2|中|输入校验-字段无格式验证|BackupConfig.kt, ConfigFragment.kt|78-136,200-217|
|2.3|中|输入校验-路径注入风险|ResticRestBridge.kt|62-117|
|3.2|中|敏感数据-SSAID 明文备份|BackupOperation.kt|331-347|
|3.3|中|敏感数据-WiFi 配置含密码|WifiManager.kt|41-47|
|5.2|中|安全配置-无 networkSecurityConfig|AndroidManifest.xml|12-18|
|6.1|中|日志泄露-命令内容|RootShell.kt|82,85|
|6.3|中|日志泄露-文件日志含敏感信息|LogUtil.kt|45-58|
|1.1|低|授权-无权限检查模式|BackupOperation.kt|多处|
|1.2|低|配置-冗余 libsu 日志|RootShell.kt|54|
|4.2|低|API-错误信息泄露|ResticRestBridge.kt|47-51,207-210|
|6.4|低|日志泄露-URL 可能含凭据|ConfigViewModel.kt|178|

---

## 最重要的修复建议（按优先级排序）

1. **（紧急）修复 ResticRestBridge 绑定到 0.0.0.0** — 改为仅监听 127.0.0.1，防止局域网内其他设备访问 REST 桥接 API。
2. **（紧急）设置 allowBackup="false"** — 防止 ADB 备份提取明文密码配置文件。
3. **（高优先级）移除 SSAID 值日志输出** — `BackupOperation.kt:345` 中删除 `= $value` 部分。
4. **（高优先级）对备份配置使用加密存储** — 使用 `EncryptedSharedPreferences` 或运行时密码输入，避免密码明文持久化。
5. **（中优先级）添加输入验证层** — 对 `resticBackendUrl` 等字段进行格式验证，所有操作前检查密码非空。
6. **（中优先级）添加 networkSecurityConfig** — 限制明文流量目标。
7. **（中优先级）审查 LogUtil 日志内容** — 确保日志文件中不包含密码/SSAID 等敏感字段。
