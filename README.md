# Android Backup GUI

Android 应用备份与恢复工具，集成 [restic](https://restic.net/) 实现增量去重备份，支持 WebDAV / SMB 远程仓库。

## 功能

- **应用扫描** — 自动列出第三方应用，支持系统应用白名单
- **APK + 数据备份** — 备份 APK 文件、应用数据目录、OBB 数据、SSAID、权限、WiFi 配置
- **并行备份/恢复** — 备份并发数 3（Semaphore(3)），恢复并发数 2（Semaphore(2)）
- **存档完整性校验** — 备份后自动 zstd/gzip 校验数据归档
- **restic 增量去重** — 内建 `librestic.so`（~24MB），支持本地和远端仓库
- **构建体积优化** — Release APK 仅 11.8 MB（ProGuard/R8 full mode + shrinkResources + BouncyCastle PQC 移除）
- **远程后端** — WebDAV（如 123 云盘）/ SMB 协议，本地临时仓库 + 自动双向同步 + 进度回调
- **配置持久化** — 仓库路径、密码、后端参数保存在 `backup_settings.conf`
- **快照管理** — 初始化仓库、查看统计、按策略清理旧快照（保留 7 天/4 周/3 月）
- **应用名显示** — 使用 `PackageManager` 解析应用名，优先显示中文名，回退包名

## 技术栈

- Kotlin / Android SDK
- [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 解析，取代 org.json）
- Coroutines + StateFlow（面向状态架构）
- Android ViewModel + lifecycle-runtime-ktx
- restic 0.17+ 二进制（编译为 `librestic.so`）
- sardine-android (WebDAV 客户端)
- SMBJ (jcifs-ng) (SMB 客户端)
- Material 3 UI
- Root shell with Mutex + timeout (120s)

## 架构

```
BackupFragment / RestoreFragment / ConfigFragment  (UI)
       │                         │
       │  viewModels()           │  observe StateFlow
       ▼                         ▼
ConfigViewModel                 ResticWrapper
  └─ StateFlow<ConfigUiState>      ├── ResticBackup       (备份)
                                   ├── ResticRestore      (恢复 + dump)
                                   ├── ResticSnapshotOps  (快照列表)
                                   ├── ResticMaintenance  (forget/prune/check/stats)
                                   ├── ResticRepoInit     (init)
                                   ├── ResticCommandRunner(进程执行)
                                   ├── ResticEnvResolver  (环境变量)
                                   ├── RemoteSyncManager  (同步编排)
                                   └── RemoteTransport    (文件传输接口)
                                         ├── WebdavTransport (sardine, 8KB 分块)
                                         └── SmbTransport    (jcifs-ng, 8KB 分块 + 签名)
```

### 数据流

```
1. BackupOperation.backupApps()  ─── 本地 APP 备份到 outputDir
                                      │
2. WifiManager.backup(outputDir)     WiFi 配置
                                      │
3. ResticWrapper.backup(paths=[outputDir])  ─── restic 快照
       │
       ├── RemoteSyncManager.withRemoteSync()
       │     ├── syncFromRemote  (WebDAV/SMB: 下载远端差异)
       │     ├── action()        (restic backup/restore 命令)
       │     └── syncToRemote    (WebDAV/SMB: 上传本地差异)
       │
       └── RemoteTransport
             ├── upload(download)
             │     ├── onProgress(TransferProgress)      ← 阶段 ("connecting" / "transferring" / "completed")
             │     └── onByteProgress(ByteProgress)      ← 8KB 粒度字节进度
             │
             └── protocol 实现: SmbTransport / WebdavTransport
```

### restic 远程仓库流程

```
1. syncFromRemote: PROPFIND 递归列出远端文件 → 下载差异文件到本地临时仓库
   → 发出 TransferProgress("list") / TransferProgress("download") / TransferProgress("delete_stale")
2. runRestic: 在本地临时仓库执行 restic 命令 (backup / restore / snapshots / init ...)
3. syncToRemote: 递归遍历本地临时仓库 → 上传差异文件到远端
   → 发出 TransferProgress("upload") / TransferProgress("delete_stale") / TransferProgress("complete")
```

远端同步基于内容大小比较，跳过同名等长文件；自动删除远端/本地过时文件。

### 关键设计

- **导航栏索引** — 使用 `FragmentPagerAdapter` + 三个子 Fragment
- **href 自引用过滤** — WebDAV 服务器常将目录自身作为 PROPFIND 响应条目，通过比较资源 href 与请求 URL 精确过滤
- **根目录 404 保护** — 根目录返回 404 视为致命错误（防止限流导致误删本地文件），子目录 404 安全跳过
- **指数退避重试** — DNS 超时、5xx 错误、连接拒绝等瞬时故障自动重试（1s/2s/4s），最多 3 次
- **双向递归同步** — BFS 遍历远端目录树，深度限制 3 层，适配 restic 仓库结构
- **双向递归递归过滤** — 脏删除（walkLocalFile filter，适配临时仓库模式）
- **SmbException 精确处理** — 区分 `STATUS_OBJECT_NAME_NOT_FOUND`(0xC0000034) / `STATUS_OBJECT_NAME_COLLISION`(0xC0000035)
- **标签解析** — `PackageManager.getApplicationLabel()` 批量解析，无 root 需求，比 `dumpsys package` 快 10x+
- **ConfigViewModel** — `StateFlow<ConfigUiState>` 驱动配置 UI，`viewModelScope` 管理 restic 操作生命周期
- **进度回调线程安全** — TransferProgress 统一由 `withRemoteSync` 分派至 Main 线程；ByteProgress 留在 IO（8KB 粒度）
- **并发安全性** — `RootShell` 使用 `Mutex` + `withTimeout(120s)`；远程同步使用 `Mutex`；`BackupOperation`/`RestoreOperation` 使用 `Semaphore` + `AtomicInteger`
- **SMB 签名可选** — `smbSigning` 构造参数（默认 true），兼容旧 SMB 服务器
- **文件大小限制** — WebDAV 上传 50MB 上限（防止 ByteArray OOM）
- **存档完整性校验** — 备份后 zstd/gzip 验证数据归档，校验失败回告

## 构建

### 版本历史

|-|版本|更新内容|
|-|---:|--------|
| | v1.3 | 累积快照、AppResult 类型化错误、RootShell Mutex、kotlinx-serialization 迁移 |
| | v1.4 | APK 体积优化（ProGuard/R8 + shrinkResources + 依赖裁剪），Release APK 从 25 MB 降至 11.8 MB（-52.8%） |

### 编译命令

```bash
# Debug APK（不压缩，适合开发调试）
./gradlew assembleDebug

# Release APK（ProGuard/R8 混淆 + 资源裁剪 + 签名）
./gradlew assembleRelease
```

> Release 构建需配置 `release.keystore` 签名文件；`librestic.so` 放在 `app/src/main/jniLibs/arm64-v8a/` 下。

## 使用说明

1. Android 设备需具备 **root 权限**（用于 `pm path`、`dumpsys`、文件访问等）
2. 在「设置」页签配置 restic 仓库参数（后端类型、URL、路径、密码）
3. 点击「初始化」创建仓库（远程后端需 WebDAV/SMB 服务已运行）
4. 在「备份」页签选择应用，点击「开始备份」
5. 在「恢复」页签选择备份目录或 restic 快照，点击「开始恢复」

### WebDAV 配置示例

| 字段 | 值 |
|------|-----|
| 后端 | WebDAV |
| 地址 | `https://webdav.123pan.cn/webdav` |
| 用户名 | 手机号 |
| 密码 | 应用密码 |
| 仓库存放路径 | `back` |
