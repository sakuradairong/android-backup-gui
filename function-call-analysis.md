# 函数调用完整分析报告

基于对代码的全面搜索，以下是各核心函数的调用情况分析。

---

## 一、备份功能调用链

### 1. **顶层入口函数**
```
BackupViewModel.executeBackup() (UI 触发)
    ↓
BackupOperation.backupApps() (核心备份)
    ↓
    ├── backupUserData() (用户数据)
    ├── backupObb() (OBB 数据)
    ├── backupExternalData() (外部数据)
    ├── backupSsaid() (SSAID)
    ├── backupPermissions() (权限)
    ├── writeFileForBackup() (元数据)
    ├── readTextFile() (读取旧元数据)
    └── buildAppDetailsJson() (构建 JSON)
```

### 2. **BackupOperation 核心函数调用统计**

| 函数 | 调用次数 | 主要调用者 |
|------|---------|-----------|
| `backupApps()` | 1 | BackupViewModel:199 |
| `backupUserData()` | 1 | BackupOperation:225 |
| `backupObb()` | 1 | BackupOperation:243 |
| `backupExternalData()` | 1 | BackupOperation:256 |
| `backupSsaid()` | 1 | BackupOperation:262 |
| `backupPermissions()` | 1 | BackupOperation:267 |
| `writeFileForBackup()` | 8 | BackupOperation(6), ResticStreamBackup(2) |
| `readTextFile()` | 6 | BackupOperation(3), RestoreScreen(2), RestoreOperation(1) |
| `backupFileSize()` | 4 | BackupOperation(3), RestoreOperation(1) |
| `backupPathExists()` | 5 | BackupOperation(2), RestoreOperation(3) |
| `buildAppDetailsJson()` | 3 | BackupOperation(2), ResticStreamBackup(1) |
| `listBackupFiles()` | 7 | RestoreScreen(2), RestoreOperation(5) |
| `mkdirsForBackup()` | 3 | BackupOperation(3) |
| `backupIsDirectory()` | 1 | (内部使用) |

---

## 二、Restic 功能调用链

### 1. **ResticWrapper 方法调用统计**

#### 备份相关
```
BackupViewModel.executeBackup()
    ├── ResticWrapper.backup() → ResticBackup.backup()
    └── ResticWrapper.backupStreaming() → ResticStreamBackup.backup()
```

#### 恢复相关
```
RestoreScreen (UI)
    ├── ResticWrapper.restore() → ResticRestore.restore()
    └── ResticWrapper.dump() → ResticRestore.dump()
```

#### 快照管理
```
ConfigViewModel
    ├── ResticWrapper.listSnapshots() → ResticSnapshotOps.listSnapshots()
    ├── ResticWrapper.forget() → ResticSnapshotOps.forget()
    ├── ResticWrapper.prune() → ResticMaintenance.prune()
    ├── ResticWrapper.unlock() → ResticMaintenance.unlock()
    └── ResticWrapper.stats() → ResticMaintenance.stats()

RestoreScreen
    └── ResticWrapper.listSnapshots()
```

#### 初始化
```
ConfigViewModel
    └── ResticWrapper.init() → ResticRepoInit.init()
```

### 2. **ResticWrapper 方法详细调用**

| 方法 | 调用次数 | 调用者 |
|------|---------|--------|
| `backup()` | 2 | BackupViewModel:299, ResticWrapper:175 |
| `backupStreaming()` | 2 | BackupViewModel:263, ResticWrapper:210 |
| `restore()` | 2 | RestoreScreen:299, ResticWrapper:245 |
| `dump()` | 3 | RestoreScreen (多处), ResticWrapper:272 |
| `listSnapshots()` | 5 | ConfigViewModel(3), RestoreScreen(1), ResticWrapper:296 |
| `forget()` | 2 | ConfigViewModel:721, ResticWrapper:320 |
| `prune()` | 2 | ConfigViewModel:750, ResticWrapper:442 |
| `unlock()` | 3 | ConfigViewModel(2), ResticWrapper:499 |
| `stats()` | 2 | ConfigViewModel:647, ResticWrapper:480 |
| `init()` | 3 | ConfigViewModel(2), ResticWrapper:123 |
| `buildRepoUrl()` | 2 | ConfigViewModel(1), ResticWrapper:512 |
| `parseAppDetailsJson()` | 4 | RestoreScreen(2), ResticWrapper:401 |
| `getLatestSnapshotAppDetails()` | 1 | (内部使用) |

---

## 三、Restic 子模块调用图

### 1. **BackendExecutor 调用链**
```
ResticBackup.backup()
    └── executor.withBackend()

ResticRestore.restore()
    └── executor.withBackend()

ResticRestore.dump()
    └── executor.withBackend()

ResticSnapshotOps.listSnapshots()
    └── executor.withBackend()

ResticSnapshotOps.forget()
    └── executor.withBackend()

ResticMaintenance (prune/unlock/check/stats)
    └── executor.runResticWithBackend()

ResticStreamBackup.backup()
    ├── executor.runResticStreamingWithBackend()
    └── executor.withBackend()
```

### 2. **ResticCommandRunner 调用统计**
```
runRestic() - 12 调用
├── ResticRepoInit.init() (3次: init, snapshots, unlock)
├── ResticRestore.dump() (1次)
├── ResticSnapshotOps.listSnapshots() (2次)
├── ResticSnapshotOps.forget() (1次)
├── ResticMaintenance (4次: prune/unlock/check/stats)
└── ResticStreamBackup.backup() (1次: 验证快照)

runResticStreaming() - 3 调用
├── ResticBackup.backup() (1次)
├── ResticRestore.restore() (1次)
└── BackendExecutor.runResticStreamingWithBackend() (1次)
```

### 3. **ResticEnvResolver 调用**
```
buildLocalEnv() - 被 BackendExecutor.withBackend() 调用 (本地后端)
buildBridgeEnv() - 被 BackendExecutor.withBackend() 调用 (远程后端)
```

### 4. **RestBridgeRunner 调用**
```
withBridge() - 被 BackendExecutor.withBackend() 调用 (远程后端)
```

---

## 四、密码管理调用图

### PasswordManager 调用统计

| 方法 | 调用次数 | 调用者 |
|------|---------|--------|
| `init()` | 1 | MainActivity:34 |
| `getResticPassword()` | 6 | BackupViewModel:258, ConfigViewModel:168,314,354, RestoreScreen:142,294,482,553 |
| `setResticPassword()` | 3 | ConfigViewModel:172,221 |
| `getBackendPass()` | 6 | BackupViewModel:259, ConfigViewModel:169,315,358, RestoreScreen:143,297,483,554 |
| `setBackendPass()` | 3 | ConfigViewModel:175,224 |
| `isInitialized()` | 0 | (未使用) |
| `hasResticPassword()` | 0 | (未使用) |
| `clearAll()` | 0 | (未使用) |

---

## 五、辅助工具调用图

### 1. **BinaryResolver**
```
tarPath() - 3 调用
├── BackupOperation.backupUserData() :350
└── RestoreOperation (2处) :57,58

zstdPath() - 3 调用
├── BackupOperation.backupUserData() :354
└── RestoreOperation (2处) :58
```

### 2. **ResticBinary**
```
prepare() - 4 调用
├── MainActivity:30
├── ConfigViewModel:373
├── BackupViewModel:254
└── RestoreScreen:135
```

### 3. **AppScanner**
```
getApkPaths() - 3 调用
├── BackupOperation:158 (备份时)
├── BackupOperation:660 (构建 JSON 时)
└── ResticStreamBackup:88

hasObbData() - 1 调用
└── BackupOperation:240

hasKeystore() - 1 调用
└── BackupOperation:176

extractIcon() - 1 调用
└── BackupOperation:265
```

### 4. **WifiManager**
```
backup() - 1 调用
└── BackupViewModel:223

restore() - 1 调用
└── RestoreScreen (UI)
```

---

## 六、RootShell 调用分布

### 按模块统计

| 模块 | RootShell.exec 调用次数 |
|------|----------------------|
| BackupOperation | ~55 |
| RestoreOperation | ~30 |
| AppScanner | ~10 |
| ResticStreamBackup | ~10 |
| WifiManager | ~10 |
| SELinuxUtil | ~3 |
| **总计** | **~118** |

### 常见操作类型

1. **文件操作** (cp, mkdir, rm, chmod, chown) - ~40%
2. **包管理** (pm install, pm list, pm grant/revoke) - ~20%
3. **状态检查** (test -d, stat, ls) - ~15%
4. **系统信息** (dumpsys, pidof, settings) - ~15%
5. **压缩/归档** (tar, zstd, gzip) - ~10%

---

## 七、调用关系发现的问题

### 1. **未使用的函数**
```
PasswordManager.isInitialized() - 0 调用
PasswordManager.hasResticPassword() - 0 调用
PasswordManager.clearAll() - 0 调用
```
**建议**: 这些函数要么添加调用，要么标记为 public API 供外部使用。

### 2. **重复的密码获取逻辑**
```kotlin
// BackupViewModel:258-259
val password = PasswordManager.getResticPassword() 
    ?: s.config.resticPassword.takeIf { it.isNotEmpty() } ?: ""

// ConfigViewModel:168-169
val password = PasswordManager.getResticPassword() 
    ?: c.resticPassword.takeIf { it.isNotEmpty() }
```
**问题**: 相同的密码获取逻辑在 3+ 处重复。  
**建议**: 提取为公共函数 `getEffectiveResticPassword()`。

### 3. **RootShell 调用过多**
- BackupOperation 中约 55 次 RootShell.exec 调用
- 很多是简单的文件存在性检查或状态查询
- 可以优化为批量操作或缓存

### 4. **错误处理不一致**
- 有些 RootShell 调用检查了 `isSuccess`，有些没有
- 某些调用使用 `?.isSuccess`，某些使用 `.isSuccess`
- 建议统一错误处理模式

### 5. **调用深度过深**
```
BackupViewModel.executeBackup()
  → BackupOperation.backupApps()
    → backupUserData()
      → runTar()
        → RootShell.exec()
```
**层数**: 5 层  
**建议**: 考虑扁平化某些调用，减少复杂性。

---

## 八、性能优化建议

### 1. **减少 RootShell 调用**
```kotlin
// 当前：多次独立调用
val exists = RootShell.exec("test -d '$path1'")
val size = RootShell.exec("stat -c%s '$path2'")
val perms = RootShell.exec("stat -c%a '$path3'")

// 优化：单次批量调用
val info = RootShell.exec("""
    test -d '$path1' && echo "EXISTS" || echo "NOT_EXISTS"
    stat -c%s '$path2'
    stat -c%a '$path3'
""")
```

### 2. **缓存应用信息**
```kotlin
// 当前：每次都查询
val version = RootShell.exec("dumpsys package '$pkg' | grep versionCode")

// 优化：查询一次，缓存结果
val versionCache = ConcurrentHashMap<String, String>()
fun getVersion(pkg: String) = versionCache.getOrPut(pkg) {
    RootShell.exec("dumpsys package '$pkg' | grep versionCode")
}
```

### 3. **并行执行独立操作**
```kotlin
// 当前：串行执行
backupSsaid(pkg, appDir, userId)
backupPermissions(pkg, appDir)
extractIcon(pkg, appDir, userId)

// 优化：并行执行（注意线程安全）
coroutineScope {
    async { backupSsaid(pkg, appDir, userId) }
    async { backupPermissions(pkg, appDir) }
    async { extractIcon(pkg, appDir, userId) }
}
```

---

## 九、调用图可视化

### 备份流程
```
User Click → BackupViewModel.executeBackup()
  ├─ BackupOperation.backupApps()
  │   ├─ [并发] backupUserData() → runTar() → RootShell.exec()
  │   ├─ [并发] backupObb() → RootShell.exec()
  │   ├─ [并发] backupExternalData() → RootShell.exec()
  │   ├─ backupSsaid() → RootShell.exec()
  │   ├─ backupPermissions() → RootShell.exec()
  │   ├─ AppScanner.*()
  │   └─ buildAppDetailsJson() → writeFileForBackup()
  │
  ├─ WifiManager.backup() → RootShell.exec()
  │
  └─ [可选] ResticWrapper.backup()
      └─ ResticBackup.backup()
          └─ executor.withBackend()
              └─ runner.runResticStreaming()
                  └─ ProcessBuilder → restic CLI
```

### 恢复流程
```
User Click → RestoreScreen
  ├─ ResticWrapper.restore()
  │   └─ ResticRestore.restore()
  │       └─ executor.withBackend()
  │           └─ runner.runResticStreaming()
  │
  └─ RestoreOperation.restoreApps()
      ├─ installApk() → RootShell.exec()
      ├─ restoreUserData() → RootShell.exec()
      ├─ restoreObb() → RootShell.exec()
      ├─ restoreExternalData() → RootShell.exec()
      ├─ restoreSsaid() → RootShell.exec()
      └─ restorePermissions() → RootShell.exec()
```

---

## 十、总结

### 调用统计
- **RootShell.exec**: ~118 次调用
- **Restic CLI**: ~15 次调用 (通过 ResticCommandRunner)
- **文件 I/O**: ~30 次调用 (writeFileForBackup, readTextFile)
- **密码管理**: ~20 次调用 (PasswordManager)

### 发现的问题
1. ✅ **密码获取逻辑重复** - 3+ 处相同代码
2. ✅ **未使用的函数** - 3 个 PasswordManager 方法
3. ✅ **RootShell 调用过多** - 可优化为批量操作
4. ✅ **错误处理不一致** - 需要统一标准
5. ✅ **调用深度过深** - 5 层嵌套

### 优化优先级
1. **P1**: 提取密码获取公共函数，消除重复
2. **P2**: 统一错误处理模式
3. **P3**: 实现 RootShell 批量调用优化
4. **P4**: 实现应用信息缓存机制
