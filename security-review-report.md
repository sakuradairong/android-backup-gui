# Android Backup GUI — 安全审查报告

**审查日期**: 2026-06-06  
**审查范围**: 37 个 Kotlin 源文件  
**审查技能**: 安全漏洞检测（注入、Secret 泄露、权限滥用、路径遍历）

---

## 严重程度分级说明

| 等级 | 定义 |
|------|------|
| CRITICAL | 可直接导致 root 提权、用户数据泄露或远程命令执行的漏洞。必须立即修复。 |
| HIGH | 在特定条件下可导致敏感数据泄露或越权访问。应在下一版本修复。 |
| MEDIUM | 安全风险较低，或需要复杂攻击链才能利用。建议规划修复。 |
| LOW | 信息泄露风险极低，或设计上可接受但不够理想。可选修复。 |

---

## 发现汇总

| # | 严重程度 | 类别 | 文件 | 行号 |
|---|----------|------|------|------|
| 1 | **CRITICAL** | Secret 泄露 | `BackupConfig.kt` | 69, 73, toFile() |
| 2 | **CRITICAL** | Secret 泄露 | `BackupConfig.kt` | toFile() |
| 3 | **HIGH** | 认证缺失 | `ResticRestBridge.kt` | 27 |
| 4 | **HIGH** | 路径遍历/越权写入 | `RestoreOperation.kt` | restoreData() |
| 5 | **MEDIUM** | 命令注入(Sed) | `RestoreOperation.kt` | 250-253 |
| 6 | **MEDIUM** | 信息泄露(Logcat) | `RootShell.kt` | 55 |
| 7 | **MEDIUM** | 路径遍历 | `ResticRestBridge.kt` | 246-257 |
| 8 | **MEDIUM** | 信息泄露(Logcat) | `ResticCommandRunner.kt` | 40-41 |
| 9 | **MEDIUM** | 加密/安全存储 | `BackupConfig.kt` | 68-73 |
| 10 | **LOW** | 缺少参数验证 | `AppScanner.kt` | 多处 |
| 11 | **LOW** | SMB 签名关闭 | `SmbTransport.kt` | 26 |
| 12 | **LOW** | 证书固定缺失 | `WebdavTransport.kt` | 22-28 |

---

## 详细发现

### 🔴 CRITICAL: 凭据明文存储在配置文件中

**文件**: `BackupConfig.kt` 第 69 行  
**文件**: `BackupConfig.kt` 第 73 行  
**文件**: `BackupConfig.kt` toFile() 方法

```kotlin
// 第 69 行
val resticPassword: String = "",
// 第 73 行
val resticBackendPass: String = "",
```

**问题**: Restic 仓库密码、SMB/WebDAV 密码以明文形式存储在 `backup_settings.conf` 文件中。配置文件位于 `filesDir/backup_settings.conf`，在 root 权限下对任何进程可读。`toFile()` 方法（~第 156-157 行）将密码直接写入文件：

```kotlin
appendLine("restic_password=\"${config.resticPassword}\"")
appendLine("restic_backend_pass=\"${config.resticBackendPass}\"")
```

此外，UI 中密码以明文显示和编辑（`ConfigFragment.kt` 第 151 行）。

**风险**: 任何具有 root 权限的进程（或通过漏洞获得 root 的恶意应用）可读取这些凭据。如果用户使用相同的 restic 密码保护多个设备，泄露范围会扩大。

**建议**:
1. 使用 Android `EncryptedSharedPreferences` 存储密码（加密后存储在配置目录）
2. 密码字段在 UI 中使用 `inputType="textPassword"` 隐藏显示
3. 考虑使用 Android Keystore 进行密钥管理
4. 配置文件设置为仅 app 自身可读（`MODE_PRIVATE`，但 root 环境下效果有限）

---

### 🔴 CRITICAL: 配置文件写入默认权限不安全

**文件**: `BackupConfig.kt` — `toFile()` 方法（~第 144 行）

```kotlin
fun toFile(config: BackupConfig, file: File) {
    file.parentFile?.mkdirs()
    file.writeText(buildString { ... })
}
```

**问题**: `file.writeText()` 使用系统默认文件权限。在 Android 上，`filesDir` 中的文件默认模式为 `MODE_PRIVATE`，但 root 权限环境绕过此保护。此外没有任何文件权限的显式设置。

**建议**: 保存配置文件后显式设置权限：
```kotlin
file.setReadable(true, true)  // owner-only readable
file.setWritable(true, true)  // owner-only writable
```

考虑迁移到 Android KeyStore + EncryptedSharedPreferences。

---

### 🔴 HIGH: ResticRestBridge 绑定到所有网络接口且无认证

**文件**: `ResticRestBridge.kt` 第 27 行

```kotlin
class ResticRestBridge(...) : NanoHTTPD(0) {
```

**问题**: `NanoHTTPD(0)` 绑定到 `0.0.0.0`（所有网络接口），随机端口。而桥接 URL 使用的是 `127.0.0.1`（`RestBridgeRunner.kt` 第 72 行），但服务器本身对所有接口开放。该桥接提供无需任何认证的完整备份仓库读写访问（`GET`/`POST`/`DELETE` blob、`HEAD` 检查、`list` 操作）。

**风险**: 设备上任何进程（不需要 root）都可以扫描开放端口、连接到桥接，并读取或写入备份仓库。由于桥接在随机端口上运行且生命周期短暂，利用难度稍高但仍存在。

**建议**:
1. 使用 `ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))` 或 NanoHTTPD 的 `bindAddr` 参数显式绑定到 localhost
2. 添加认证令牌（restic REST API 支持 token 认证）
3. 限制响应时间窗口，使用后立即删除 blob

---

### 🔴 HIGH: Tar 解压使用 `-C /` 可能导致系统文件覆写

**文件**: `RestoreOperation.kt` — `restoreData()` 方法（~第 137-149 行）

```kotlin
val baseCmd = when {
    archive.name.endsWith(".zst") ->
        "set -o pipefail; $zstdCmd -d -c '$archivePath' | $tarCmd -xf - $excludeArgs -C / 2>/dev/null"
    ...
}
```

**问题**: 备份存档使用根目录 `/` 解压。`isArchiveSafe` 方法（~第 220-232 行）仅检查 `..` 路径穿越和指向外部的符号链接，但**不检查**：
- 存档中的绝对路径条目（如 `/etc/passwd`、`/system/bin/app_process`）
- 硬链接（可绕过 `..` 检查）
- 设备节点
- 解压总量（可用于磁盘空间耗尽攻击）

如果攻击者能够修改备份文件（例如通过恶意 App 访问外部存储），解压操作可覆写任意系统文件。

**建议**:
1. 添加对绝对路径的检查 —— 拒绝包含 `/` 前缀路径（绝对路径）的存档
2. 使用 `isArchiveSafe` 补充绝对路径检测：`line.startsWith("/")`
3. 考虑使用 `--strip-components` 选项或在临时目录解压后再移动到目标路径
4. 添加存档大小和解压条目数量上限

---

### 🟡 MEDIUM: SSAID 恢复中的 Sed 命令注入风险

**文件**: `RestoreOperation.kt` 第 250-253 行

```kotlin
val manipCmd = buildString {
    append("sed -i \"/package.*${packageName.shellEscape()}/d\" '$targetFile' && ")
    append("sed -i \"s#</settings>#<setting id=\\\"$id\\\" package=\\\"${packageName.shellEscape()}\\\" value=\\\"${ssaidValue.shellEscape()}\\\" defaultValue=\\\"default\\\" />\\n</settings>#\" '$targetFile'")
}
```

**问题**: `ssaidValue` 和 `packageName` 虽然经过了 `shellEscape()`（处理单引号），但 Sed 模式中使用 `#` 作为分隔符。如果 `ssaidValue` 包含 `#`（UUID 不可能，但从文件读取的 SSAID 可能包含任意字符），会破坏 Sed 命令结构。此外，`shellEscape()` 只处理 shell 层的单引号，不处理 Sed 层的 `\`、`&`、`/` 等特殊字符。

**风险**: 若攻击者可通过修改 `ssaid.txt` 文件插入恶意 Sed 表达式，可能导致任意文件写入。

**建议**: 
1. 使用纯 Kotlin XML 解析（如 `XmlPullParser`）操作 `settings_ssaid.xml`，而不是 Sed
2. 或使用 `sed -e` 的分隔符参数引用，并验证 `ssaidValue` 只包含十六进制字符

---

### 🟡 MEDIUM: RootShell 启用了 libsu 详细日志

**文件**: `RootShell.kt` 第 55 行

```kotlin
Shell.enableVerboseLogging = true
```

**问题**: libsu 的详细日志会将所有 shell 命令输出到 Logcat。Logcat 在 Android 上对任何具有 `READ_LOGS` 权限的应用可读。这可能导致命令路径、参数、错误消息等信息泄露。

**风险**: 调试期间有助于开发，但生产版本应禁用。命令本身不包含密码（通过环境变量传递），但路径结构和目录名可能暴露敏感信息。

**建议**: 
1. 根据构建类型控制日志级别：
```kotlin
Shell.enableVerboseLogging = BuildConfig.DEBUG
```
2. 或完全移除该行

---

### 🟡 MEDIUM: ResticRestBridge JSON 手动拼接存在注入风险

**文件**: `ResticRestBridge.kt` 第 246-257 行

```kotlin
private fun buildV2Json(items: List<RemoteTransport.RemoteFileInfo>): String {
    val sb = StringBuilder("[")
    var first = true
    for (item in items) {
        ...
        sb.append("{\"name\":\"${item.name}\",\"size\":${item.size}}")
    }
    sb.append("]")
    return sb.toString()
}
```

**问题**: 文件名 `item.name` 直接插值到 JSON 字符串中。若远程存储上的文件名包含 `"`、`\\`、`\n` 等字符，会破坏 JSON 结构，可能导致解析错误或意外的数据暴露。

**风险**: 文件名来自远程存储（SMB/WebDAV），攻击者可能控制这些名称。返回给 restic 的损坏 JSON 可能导致备份操作失败或状态误报。

**建议**: 使用 `kotlinx-serialization` 或 `JSONArray` 构建 JSON。

---

### 🟡 MEDIUM: ResticCommandRunner 日志暴露仓库 URL

**文件**: `ResticCommandRunner.kt` 第 40-41 行

```kotlin
Log.d(TAG, "runRestic REPOSITORY=${env["RESTIC_REPOSITORY"]}")
```

**问题**: 虽然代码注释正确指出 `RESTIC_PASSWORD` 不应记录，`RESTIC_REPOSITORY` 仍可能包含 SMB 共享名称、仓库路径等敏感信息。Logcat 可被其他应用读取。

**建议**: 至少将敏感部分截断或哈希，或仅在 DEBUG 构建下记录。

---

### 🟡 MEDIUM: 多个凭据未加密存储在内存中

**文件**: `BackupConfig.kt` 第 68-73 行

```kotlin
val resticPassword: String = "",
val resticBackendUser: String = "",
val resticBackendPass: String = "",
```

**问题**: `BackupConfig` 作为 `@Serializable data class`，所有密码字段在进程生命周期内以不可变字符串形式保存在内存中。字符串在 Java 中不可变，无法显式清除（零覆盖）。

此外，`ResticWrapper` 的所有公开 API 方法都将密码作为方法参数传递，导致 Activity/Fragment/ViewModel 中密码的副本散布各处。

**建议**:
1. 通过值对象传递密码，操作完成后立即清除
2. 考虑使用 `CharArray` 并在使用后填充空白
3. 在传递之间最小化密码在 Kotlin 对象图中的驻留时间

---

### 🟢 LOW: AppScanner 中 userId 参数缺少非负验证

**文件**: `AppScanner.kt` 多处

```kotlin
suspend fun scanThirdParty(context: Context, userId: Int = 0): List<AppInfo> = withContext(Dispatchers.IO) {
    val result = RootShell.exec("pm list packages -3 --user $userId")
```

**问题**: `userId` 虽然类型为 `Int`，直接插值到 shell 命令中。如果传入负数（如 -1），可能导致意外行为。但 `userId` 来自 Spinner 选择或 `UserId` 值类（已验证非负），因此实际风险很低。

**建议**: 在 UI 层、`UserId` 值类或 `AppScanner` 入口处增加正数验证。

---

### 🟢 LOW: SMB 传输默认关闭签名/加密

**文件**: `SmbTransport.kt` 第 26 行

```kotlin
private val smbSigning: Boolean = false
```

**问题**: SMB 签名和加密默认禁用。在不安全的网络中，攻击者可进行 SMB 中继攻击。代码注释说明"多数家庭服务器不支持"，是合理的取舍。

**建议**: 在 UI 配置页面添加 SMB 签名开关，让用户根据网络环境决定。

---

### 🟢 LOW: WebDAV 传输缺少证书固定

**文件**: `WebdavTransport.kt` 第 22-28 行

```kotlin
private val sardine: Sardine by lazy {
    OkHttpSardine().apply {
        if (username.isNotEmpty()) {
            setCredentials(username, password)
        }
    }
}
```

**问题**: `OkHttpSardine` 使用默认的 HTTPS 配置，没有自定义证书验证或证书固定（Certificate Pinning）。中间人攻击（MITM）可窃取 WebDAV 的备份凭据。

**建议**: 对于重视安全的场景，可选支持证书固定，或在 UI 中显示当前 HTTPS 证书指纹。

---

## 正向发现（设计良好的安全实践）

| 实践 | 文件 | 说明 |
|------|------|------|
| ✅ 密码通过环境变量传递 | `ResticEnvResolver.kt` | RESTIC_PASSWORD 通过 env 传递，不在命令行中出现 |
| ✅ `shellEscape()` 一致使用 | `root/RootShell.kt:15` | 所有 shell 拼接参数都经过了转义 |
| ✅ `execSafe()` 安全方法 | `root/RootShell.kt:95-101` | 提供自动参数转义的执行方法 |
| ✅ ProcessBuilder 列表参数 | `ResticCommandRunner.kt` | 使用 List<String> 参数，无 shell 拼接 |
| ✅ `isArchiveSafe()` 路径穿越检查 | `RestoreOperation.kt:220-232` | 解压前检查 `..` 和危险符号链接 |
| ✅ 类型安全的值类 | `DomainTypes.kt` | `PackageName` 和 `UserId` 提供编译期类型安全 |
| ✅ 定时命令超时 | `RootShell.kt:26` | 120 秒超时防止命令挂死 |
| ✅ 取消传播 | 多处 | CancellationException 正确重新抛出 |
| ✅ RESTIC_PASSWORD 不记录日志 | `ResticCommandRunner.kt:42` | 明确注释不记录密码 |

---

## 风险优先级建议

### 立即修复 (CRITICAL)
1. **BackupConfig.kt**: 使用 `EncryptedSharedPreferences` 替换明文配置存储
2. **BackupConfig.kt**: 保存后设置文件权限为 `MODE_PRIVATE`

### 下一版本修复 (HIGH)
3. **ResticRestBridge.kt**: 绑定到 127.0.0.1 而非 0.0.0.0
4. **RestoreOperation.kt**: `isArchiveSafe` 增加绝对路径检查；解压到临时目录再移动

### 规划修复 (MEDIUM)
5. **RestoreOperation.kt**: SSAID XML 操作改为 XML 解析器而非 Sed
6. **ResticRestBridge.kt**: JSON 改用序列化库构建
7. **RootShell.kt**: 生产环境禁用详细日志
8. **ResticCommandRunner.kt**: 截断或保护仓库 URL 日志

### 可选改进 (LOW)
9. **SmbTransport.kt**: 考虑默认启用 SMB 签名
10. **WebdavTransport.kt**: 可选证书固定支持
11. **AppScanner.kt**: 添加 userId 验证

---

*注意: 本报告未包含 `memory://root/memory_summary.md` 中记录的 7 个已知待处理项。*
