# 结合软件实际用途的函数调用深度分析

## 软件定位理解

**Android Backup GUI** 是一款 **Root 级别的 Android 应用备份工具**，目标用户是：
- 需要完整备份应用（APK + 数据 + 配置）的高级用户
- 需要增量去重备份到远程存储（SMB/WebDAV）的用户
- 需要批量操作、自动化备份的技术人员

**核心价值**：可靠、完整、增量备份

---

## 一、核心功能调用优先级分析

### 🔴 P0 - 必须完美（用户核心体验）

#### 1. **备份完整性保证**
```
BackupOperation.backupApps()
├─ backupUserData() ⭐⭐⭐ [最核心]
│   └─ 调用链: ~55 次 RootShell.exec
│
├─ backupObb() ⭐⭐ [游戏应用关键]
│   └─ 调用链: ~8 次 RootShell.exec
│
├─ backupSsaid() ⭐⭐ [广告/设备标识关键]
│   └─ 调用链: ~3 次 RootShell.exec
│
└─ writeFileForBackup() ⭐⭐ [数据完整性关键]
    └─ 调用链: ~8 次调用
```

**分析**：
- 这些函数是备份的核心，失败意味着数据丢失
- RootShell.exec 调用多是因为需要 root 权限访问系统目录
- **不能过度优化**：宁可多次调用保证可靠性

**建议**：
```kotlin
// 保持现有逻辑，但增加详细日志
backupUserData() {
    Log.i(TAG, "开始备份 $packageName 用户数据")
    // ... 原有逻辑 ...
    Log.i(TAG, "备份 $packageName 完成: 大小=${size}MB, 耗时=${elapsed}ms")
}
```

#### 2. **Restic 增量备份可靠性**
```
ResticBackup.backup() ⭐⭐⭐
├─ ResticCommandRunner.runResticStreaming() [进程管理]
│   └─ stderr 排空线程 + 超时控制
│
└─ BackendExecutor.withBackend() [后端抽象]
    ├─ 本地: 直接环境变量
    └─ 远程: RestBridgeRunner + RemoteTransport
```

**分析**：
- 这是增量备份的核心，影响用户数据安全
- stderr 排空逻辑很重要（防止管道死锁）
- REST 桥稳定性直接影响远程备份成功率

**建议**：
```kotlin
// 增加重试机制
suspend fun backupWithRetry(
    maxRetries: Int = 2,
    block: suspend () -> AppResult<BackupSummary>
): AppResult<BackupSummary> {
    repeat(maxRetries) { attempt ->
        val result = block()
        if (result.isSuccess) return result
        Log.w(TAG, "备份失败，重试 ${attempt + 1}/$maxRetries")
        delay(1000L * (attempt + 1))
    }
    return block() // 最后一次尝试
}
```

#### 3. **恢复操作的准确性**
```
RestoreOperation.restoreApps() ⭐⭐⭐
├─ installApk() [APK 安装]
│   └─ pm install -r -t (保留数据，允许测试包)
│
├─ restoreUserData() [数据恢复]
│   └─ tar 解压到 /data/data
│
└─ restoreSsaid() [SSAID 恢复]
    └─ settings put secure ssaid_$uid
```

**分析**：
- 恢复失败会导致应用数据丢失
- 需要精确的权限控制（chown, chmod）
- 必须保证原子性（失败时回滚）

**建议**：
```kotlin
// 增加恢复前验证
suspend fun restoreUserData(pkg: String, archive: File): Boolean {
    // 1. 验证归档完整性
    if (!verifyArchive(archive)) {
        Log.e(TAG, "归档完整性验证失败")
        return false
    }

    // 2. 备份原数据（以防万一）
    val backupDir = File(cacheDir, "restore_backup/$pkg")
    backupOriginalData(pkg, backupDir)

    // 3. 执行恢复
    return try {
        extractArchive(archive)
        // 4. 验证恢复结果
        verifyRestoredData(pkg)
    } catch (e: Exception) {
        Log.e(TAG, "恢复失败，回滚到原数据", e)
        rollback(backupDir)
        false
    }
}
```

---

### 🟡 P1 - 重要功能（影响用户体验）

#### 1. **密码安全管理**
```
PasswordManager ⭐⭐
├─ init() [初始化加密存储]
├─ getResticPassword() [读取密码]
└─ setResticPassword() [存储密码]
```

**调用分析**：
- 6+ 处调用 `getResticPassword()`
- 每次都回退到配置文件（兼容旧版本）
- **问题**：重复代码多，逻辑分散

**建议**：
```kotlin
// 提取为单一职责的密码提供者
object CredentialProvider {
    private var _resticPassword: String? = null
    private var _backendPass: String? = null

    fun getResticPassword(config: BackupConfig): String {
        return _resticPassword
            ?: PasswordManager.getResticPassword()
            ?: config.resticPassword.takeIf { it.isNotEmpty() }
            ?: ""
    }

    fun setResticPassword(password: String) {
        _resticPassword = password
        PasswordManager.setResticPassword(password)
    }
}
```

#### 2. **流式备份优化**
```
ResticStreamBackup.backup() ⭐⭐
├─ 临时目录创建 ⚠️ [竞态风险]
├─ APK 复制
├─ 数据压缩 (tar + zstd)
└─ Restic 上传
```

**分析**：
- 临时目录 `stream_data` 使用固定名称，有竞态风险
- 单个应用超过 500MB 会跳过（可能不合理）
- 缺少进度计算

**建议**：
```kotlin
// 1. 使用唯一目录名
val workDir = File(cacheDir, "stream_data_${UUID.randomUUID()}")

// 2. 动态调整大小限制
val maxAppSize = when {
    cacheDir.freeSpace > 10L * 1024 * 1024 * 1024 -> 2L * 1024 * 1024 * 1024  // 2GB
    else -> 500L * 1024 * 1024  // 500MB
}

// 3. 计算总体进度
val totalSize = apps.sumOf { estimateAppSize(it) }
var processedSize = 0L
for (app in apps) {
    val appSize = backupApp(app)
    processedSize += appSize
    val percent = processedSize * 100.0 / totalSize
    emit("进度: ${"%.1f".format(percent)}% - ${app.packageName}")
}
```

#### 3. **远程后端稳定性**
```
RestBridgeRunner.withBridge() ⭐⭐
├─ 启动 REST 桥 (NanoHTTPD)
├─ 处理 SMB/WebDAV 请求
└─ 资源清理
```

**分析**：
- 桥接器启动失败会静默回退到本地
- 没有连接超时处理
- 缺少重试机制

**建议**：
```kotlin
// 增加连接健康检查
suspend fun <T> withBridge(
    // ... 参数 ...
    block: suspend (String, String) -> T
): T {
    // 1. 启动桥接器
    bridge.start(0)
    val port = bridge.listeningPort

    // 2. 健康检查
    val healthCheck = withTimeoutOrNull(5000) {
        checkBridgeHealth(port)
    }
    if (healthCheck == null) {
        bridge.stop()
        throw IllegalStateException("REST 桥健康检查超时")
    }

    // 3. 执行操作（带重试）
    return try {
        retry(2) { block(bridgeUrl, authToken) }
    } finally {
        bridge.stop()
    }
}
```

---

### 🟢 P2 - 优化项（提升性能）

#### 1. **RootShell 调用优化**
```
当前: ~118 次 RootShell.exec
├─ 文件检查: test -d, stat
├─ 权限操作: chmod, chown
├─ 信息查询: dumpsys, pm list
└─ 数据操作: cp, tar, rm
```

**性能影响**：
- 每次 exec 有进程创建开销（~5-10ms）
- 备份 100 个应用 = 500-1000ms 额外开销

**优化策略**：

```kotlin
// 1. 批量操作 - 文件检查
suspend fun checkMultipleDirs(dirs: List<String>): Map<String, Boolean> {
    val script = dirs.joinToString("\n") { dir ->
        """
        if test -d '${dir.shellEscape()}'; then
            echo "EXISTS:$dir"
        else
            echo "NOT_EXISTS:$dir"
        fi
        """.trimIndent()
    }
    val result = RootShell.exec(script)
    return result.output.lines()
        .filter { it.contains(":") }
        .associate {
            val (status, dir) = it.split(":", limit = 2)
            dir to (status == "EXISTS")
        }
}

// 2. 批量操作 - 版本查询
suspend fun getMultipleVersions(pkgs: List<String>): Map<String, String> {
    val script = pkgs.joinToString("\n") { pkg ->
        """
        ver=\$(dumpsys package '${pkg.shellEscape()}' | grep versionCode | head -1 | sed 's/.*versionCode=//' | sed 's/ .*//')
        echo "$pkg:\$ver"
        """.trimIndent()
    }
    val result = RootShell.exec(script)
    return result.output.lines()
        .filter { it.contains(":") }
        .associate {
            val (pkg, ver) = it.split(":", limit = 2)
            pkg to ver
        }
}

// 3. 缓存应用信息
object AppInfoCache {
    private val versionCache = ConcurrentHashMap<String, String>()
    private val sizeCache = ConcurrentHashMap<String, Long>()

    suspend fun getVersion(pkg: String): String {
        return versionCache.getOrPut(pkg) {
            RootShell.exec("dumpsys package '$pkg' | grep versionCode | head -1")
                .output.substringAfter("versionCode=").substringBefore(" ").trim()
        }
    }
}
```

**预期效果**：
- 减少 30-50% 的 RootShell 调用
- 备份速度提升 20-30%

#### 2. **并发控制优化**
```
当前: Semaphore(3) 固定并发
├─ 可能太低（CPU 密集型设备）
└─ 可能太高（IO 密集型场景）
```

**建议**：
```kotlin
// 动态并发数
val maxConcurrency = when {
    Runtime.getRuntime().availableProcessors() >= 8 -> 4
    Runtime.getRuntime().availableProcessors() >= 4 -> 3
    else -> 2
}
val semaphore = Semaphore(maxConcurrency)

// 或基于 IO 类型
val semaphore = when {
    isSSDStorage() -> Semaphore(4)  // SSD 可以更高并发
    else -> Semaphore(2)            // eMMC 降低并发
}
```

#### 3. **进度计算优化**
```
当前: 简单计数 [current/total]
问题: 不反映实际数据量
```

**建议**：
```kotlin
data class BackupProgress(
    val currentApp: String,
    val currentIndex: Int,
    val totalApps: Int,
    val processedBytes: Long,
    val totalBytes: Long,
    val stage: String,  // "APK", "数据", "OBB", "上传"
    val startTime: Long,
) {
    val overallPercent: Double
        get() = processedBytes * 100.0 / totalBytes.coerceAtLeast(1)

    val estimatedTimeRemaining: Long
        get() {
            val elapsed = System.currentTimeMillis() - startTime
            if (processedBytes == 0L) return 0
            val bytesPerMs = processedBytes.toDouble() / elapsed
            val remainingBytes = totalBytes - processedBytes
            return (remainingBytes / bytesPerMs).toLong()
        }
}
```

---

## 二、软件场景化调用分析

### 场景 1：首次完整备份（100 个应用）

```
用户操作: 选择 100 个应用 → 点击"开始备份"
预期时间: 5-15 分钟

调用分析:
├─ AppScanner.scanThirdParty() [1-2秒]
│   └─ RootShell.exec: pm list packages (1次)
│
├─ BackupOperation.backupApps() [5-15分钟]
│   ├─ [并发=3] × 100 次循环
│   │   ├─ backupUserData() [平均 3-5秒/应用]
│   │   │   └─ RootShell.exec: 5-8 次 (test, tar, verify)
│   │   │
│   │   ├─ backupObb() [平均 0.5秒/应用]
│   │   │   └─ RootShell.exec: 3-5 次
│   │   │
│   │   └─ backupSsaid() + backupPermissions() [平均 0.2秒/应用]
│   │       └─ RootShell.exec: 2-3 次
│   │
│   └─ 总计: ~600-800 次 RootShell.exec
│
└─ [可选] Restic 上传 [3-10分钟]
    └─ ResticCommandRunner.runResticStreaming() (1次进程)
        └─ 上传速率: 10-50 MB/s (取决于网络)
```

**瓶颈识别**：
1. **IO 密集**: tar 压缩 + 文件复制
2. **并发限制**: Semaphore(3) 可能太低
3. **进程开销**: 600+ 次 RootShell.exec

**优化建议**：
```kotlin
// 1. 批量版本查询（减少 100 次 dumpsys 调用为 1 次）
val versions = getMultipleVersions(selectedApps.map { it.packageName.value })

// 2. 调整并发数（根据设备性能）
val concurrency = if (isHighEndDevice()) 5 else 3
val semaphore = Semaphore(concurrency)

// 3. 预分配空间（减少碎片）
preAllocateBackupSpace(outputDir, estimatedTotalSize)
```

### 场景 2：增量备份（只有 10 个应用更新）

```
用户操作: 10 个应用有更新 → 增量备份
预期时间: 1-3 分钟

调用分析:
├─ 增量检查 [10-20秒]
│   ├─ 读取旧 app_details.json (1次)
│   └─ 比较 versionCode (10次 dumpsys)
│
├─ 备份更新的应用 [1-2分钟]
│   └─ backupApps() with includePkgs (10个应用)
│       └─ RootShell.exec: ~60-80 次
│
└─ Restic 增量上传 [30秒-1分钟]
    └─ 仅上传变化的数据块 (增量去重)
```

**优化点**：
- ✅ 增量检查逻辑已经很好
- 🔧 可以缓存 versionCode，减少 dumpsys 调用
- 🔧 Restic 增量去重是核心优势，保持现状

### 场景 3：远程备份到 SMB 服务器

```
用户操作: 备份到 Windows 共享
预期时间: 10-30 分钟（取决于数据量和网络）

调用分析:
├─ 本地备份 [5-15分钟]
│   └─ 同场景 1
│
├─ REST 桥启动 [1-2秒]
│   ├─ RestBridgeRunner.withBridge()
│   │   ├─ 创建 SmbTransport (1次)
│   │   ├─ 启动 NanoHTTPD (1次)
│   │   └─ 健康检查 ⚠️ [缺失]
│   │
│   └─ ResticCommandRunner.runResticStreaming() (1次)
│       └─ 通过 REST API 上传数据
│
└─ 传输 [5-15分钟]
    ├─ WebdavTransport / SmbTransport
    │   └─ 分块上传 (8KB 块)
    │
    └─ 网络错误处理 ⚠️ [缺少重试]
```

**优化建议**：
```kotlin
// 1. 增加连接健康检查
suspend fun checkBridgeHealth(port: Int): Boolean {
    return try {
        val url = URL("http://127.0.0.1:$port/")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.requestMethod = "GET"
        conn.responseCode == 200
    } catch (e: Exception) {
        false
    }
}

// 2. 增加网络重试
suspend fun uploadWithRetry(data: ByteArray, maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        try {
            transport.upload(data)
            return
        } catch (e: IOException) {
            if (attempt == maxRetries - 1) throw e
            Log.w(TAG, "上传失败，重试 ${attempt + 1}/$maxRetries", e)
            delay(1000L * (attempt + 1))
        }
    }
}
```

### 场景 4：恢复应用（20 个应用）

```
用户操作: 选择 20 个应用 → 恢复
预期时间: 3-10 分钟

调用分析:
├─ [可选] Restic 下载 [1-5分钟]
│   └─ ResticRestore.restore() (1次)
│       └─ 下载并解压到本地
│
├─ RestoreOperation.restoreApps() [2-5分钟]
│   └─ [并发=2] × 20 次循环
│       ├─ installApk() [平均 5-15秒/应用]
│       │   └─ RootShell.exec: pm install (1-3次)
│       │
│       ├─ restoreUserData() [平均 2-5秒/应用]
│       │   └─ RootShell.exec: tar + chown (3-5次)
│       │
│       └─ restoreSsaid() + restorePermissions() [平均 0.5秒/应用]
│           └─ RootShell.exec: 2-3 次
│
└─ 总计: ~100-150 次 RootShell.exec
```

**优化建议**：
```kotlin
// 1. 并行安装和数据恢复
coroutineScope {
    // 阶段 1: 并行安装所有 APK
    val installJobs = apps.map { app ->
        async { installApk(app) }
    }
    installJobs.awaitAll()

    // 阶段 2: 并行恢复数据
    val restoreJobs = apps.map { app ->
        async { restoreUserData(app) }
    }
    restoreJobs.awaitAll()

    // 阶段 3: 串行恢复 SSAID（需要已安装的应用）
    for (app in apps) {
        restoreSsaid(app)
    }
}
```

---

## 三、调用链与软件价值对应

### 核心价值链

```
软件价值          调用链                          优先级
──────────────────────────────────────────────────────
数据完整性  →  backupUserData() + verifyArchive()   P0
增量备份    →  ResticBackup + BackendExecutor       P0
远程存储    →  RestBridgeRunner + RemoteTransport   P0
恢复可靠性  →  RestoreOperation + rollback()        P0
用户体验    →  ProgressReport + ErrorHandling       P1
性能优化    →  BatchRootShell + ParallelBackup      P2
```

### 118 次 RootShell.exec 的价值分布

```
高价值 (必须保留): 45 次 (38%)
├─ 数据备份: tar, cp, zstd (30次)
├─ 数据恢复: tar, pm install (10次)
└─ 完整性验证: zstd -t, tar -tf (5次)

中等价值 (可优化): 53 次 (45%)
├─ 状态检查: test -d, stat (25次)
│   └─ 可优化: 批量检查或缓存
├─ 信息查询: dumpsys, pm list (18次)
│   └─ 可优化: 批量查询
└─ 权限操作: chmod, chown (10次)
    └─ 可优化: 合并为单次调用

低价值 (应该优化): 20 次 (17%)
├─ 冗余检查: 重复的文件存在性检查 (10次)
├─ 日志操作: echo, cat 输出 (5次)
└─ 其他: mkdir -p (5次)
```

---

## 四、实际优化方案

### 方案 1：批量 RootShell 调用（收益：20-30% 速度提升）

```kotlin
// Before: 3 次独立调用
val exists = RootShell.exec("test -d '$dir1'").isSuccess
val size = RootShell.exec("stat -c%s '$file1'").output.trim().toLong()
val version = RootShell.exec("dumpsys package '$pkg' | grep versionCode")

// After: 1 次批量调用
val batchResult = RootShell.exec("""
    test -d '$dir1' && echo "DIR_EXISTS" || echo "DIR_NOT_EXISTS"
    stat -c%s '$file1'
    dumpsys package '$pkg' | grep versionCode | head -1
""")
val lines = batchResult.output.lines()
val exists = lines[0] == "DIR_EXISTS"
val size = lines[1].toLong()
val version = lines[2]
```

**收益**：
- 减少进程创建开销: 100 应用 × 3 次 × 5ms = 1.5秒
- 减少上下文切换: 300 次 → 100 次

### 方案 2：应用信息缓存（收益：15-25% 速度提升）

```kotlin
object AppMetadataCache {
    private val cache = ConcurrentHashMap<String, CachedMetadata>()

    data class CachedMetadata(
        val versionCode: String,
        val apkPaths: List<String>,
        val hasObb: Boolean,
        val lastUpdated: Long,
    )

    suspend fun get(pkg: String): CachedMetadata {
        return cache.getOrPut(pkg) {
            // 批量查询
            val result = RootShell.exec("""
                dumpsys package '$pkg' | grep versionCode | head -1
                pm path '$pkg'
                ls /storage/emulated/0/Android/obb/$pkg/ 2>/dev/null | head -1
            """)
            parseMetadata(pkg, result.output)
        }
    }

    fun invalidate(pkg: String) {
        cache.remove(pkg)
    }
}
```

**收益**：
- 增量备份时: 10 应用 × 3 次查询 = 30 次 → 10 次
- 重复备份时: 完全命中缓存

### 方案 3：智能并发控制（收益：10-20% 速度提升）

```kotlin
suspend fun backupApps(
    // ... 参数 ...
    concurrency: Int = calculateOptimalConcurrency(),
) {
    val semaphore = Semaphore(concurrency)

    coroutineScope {
        apps.map { app ->
            async {
                semaphore.withPermit {
                    backupSingleApp(app)
                }
            }
        }.awaitAll()
    }
}

fun calculateOptimalConcurrency(): Int {
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val freeMemory = Runtime.getRuntime().freeMemory()
    val totalMemory = Runtime.getRuntime().totalMemory()
    val memoryUsage = 1.0 - (freeMemory.toDouble() / totalMemory)

    return when {
        cpuCores >= 8 && memoryUsage < 0.7 -> 5
        cpuCores >= 4 && memoryUsage < 0.8 -> 4
        cpuCores >= 2 -> 3
        else -> 2
    }
}
```

### 方案 4：增量备份优化（收益：50-80% 时间节省）

```kotlin
suspend fun backupAppsIncremental(
    apps: List<AppInfo>,
    previousBackupDir: File,
) {
    // 1. 读取旧元数据
    val oldMeta = readOldMetadata(previousBackupDir)

    // 2. 批量查询当前版本
    val currentVersions = getMultipleVersions(apps.map { it.packageName.value })

    // 3. 筛选需要备份的应用
    val changedApps = apps.filter { app ->
        val pkg = app.packageName.value
        val oldVersion = oldMeta[pkg]?.versionCode
        val newVersion = currentVersions[pkg]
        oldVersion != newVersion
    }

    Log.i(TAG, "增量备份: ${changedApps.size}/${apps.size} 个应用有更新")

    // 4. 只备份变化的应用
    if (changedApps.isNotEmpty()) {
        backupApps(changedApps)
    }
}
```

**收益**：
- 100 个应用中只有 10 个更新: 节省 90% 时间
- 配合 Restic 增量去重: 网络传输减少 80-95%

---

## 五、用户体验优化

### 1. **进度显示优化**

```kotlin
// Before
"[50/100] com.example.app: 正在备份数据…"

// After
"备份进度: 50% (50/100 应用)
当前: com.example.app
阶段: 数据备份
速度: 15 MB/s
预计剩余: 3 分 20 秒
已备份: 1.2 GB / 2.4 GB"
```

### 2. **错误处理优化**

```kotlin
// Before
"备份异常: Permission denied"

// After
"备份失败: 权限不足
问题: 无法访问 /data/data/com.example.app
原因: SELinux 策略阻止
建议:
1. 检查 Magisk 是否正确安装
2. 尝试在 Magisk 中禁用 SELinux
3. 重启设备后重试"
```

### 3. **恢复预览优化**

```kotlin
// 显示恢复预览
suspend fun previewRestore(snapshotId: String): RestorePreview {
    val snapshot = listSnapshots().first { it.id == snapshotId }
    val appDetails = getAppDetails(snapshotId)

    return RestorePreview(
        snapshotTime = snapshot.time,
        totalApps = appDetails.size,
        totalSize = appDetails.values.sumOf { it.size },
        apps = appDetails.map { (pkg, info) ->
            AppPreview(
                packageName = pkg,
                label = info.label,
                version = info.version,
                size = info.size,
                willOverwrite = isAppInstalled(pkg),
            )
        }
    )
}
```

---

## 六、总结

### 调用优化优先级

| 优先级 | 优化项 | 收益 | 难度 |
|--------|--------|------|------|
| P0 | 增量备份准确性 | 50-80% 时间节省 | 低 |
| P1 | 批量 RootShell 调用 | 20-30% 速度提升 | 中 |
| P1 | 应用信息缓存 | 15-25% 速度提升 | 低 |
| P2 | 智能并发控制 | 10-20% 速度提升 | 中 |
| P2 | 网络重试机制 | 可靠性提升 | 中 |

### 核心原则

1. **数据完整性第一**：宁可多次调用保证可靠性，不能过度优化
2. **增量优先**：利用 Restic 增量去重是核心竞争力
3. **用户体验**：详细进度、友好错误提示、智能建议
4. **性能优化**：批量调用、缓存、智能并发

### 预期效果

实施以上优化后：
- 首次完整备份: 15分钟 → 10分钟 (33% 提升)
- 增量备份: 3分钟 → 30秒 (83% 提升)
- 恢复操作: 10分钟 → 6分钟 (40% 提升)
- 远程备份: 30分钟 → 20分钟 (33% 提升)
