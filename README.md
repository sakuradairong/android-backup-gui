# Android Backup GUI

Android 应用备份与恢复工具，通过 **root 权限** 实现应用的完整备份（APK + 用户数据 + OBB + SSAID + 权限 + WiFi 配置），集成 [restic](https://restic.net/) 实现增量去重备份，支持 SMB / WebDAV 远程仓库。

## 功能

- **应用扫描** — 自动列出第三方应用，支持系统应用白名单
- **APK + 数据备份** — 备份 APK 文件、应用数据目录、OBB 数据、SSAID、SSAID 广告标识、AppOps 权限、WiFi 配置
- **多用户支持** — 从配置页选择 Android 用户（主用户 / 工作资料），持久化到配置文件
- **并行备份/恢复** — 备份并发 3（Semaphore），恢复并发 2（Semaphore）
- **存档完整性校验** — 备份后自动 zstd/gzip 校验 + tar 结构验证
- **restic 增量去重** — 内建 `librestic.so`（~24MB），SSD 加密快照，增量备份
- **远程后端** — 本地 REST 桥 + NanoHTTPD 将 SMB/WebDAV 协议翻译为 restic 可直接访问的 REST API
- **实验性 Restic 临时目录备份** — 将备份数据暂存到临时目录后由 restic 统一上传（不包含 OBB、外部数据、权限、SSAID、Wi-Fi）
- **配置持久化** — 仓库路径、后端参数、目标用户保存在 `backup_settings.conf`；密码存储在 EncryptedSharedPreferences
- **快照管理** — 初始化、查看统计、按策略清理旧快照（保留 7 天/4 周/3 月）、解锁
- **累积快照** — 从历史快照读取元数据，合并为增量累积备份
- **应用名显示** — 备份时缓存应用名称到 `app_details.json`，已卸载应用也显示中文名
- **任务取消** — 备份和恢复支持从 UI 和通知栏取消

## 技术栈

- Kotlin / Android SDK (minSdk 24, targetSdk 34)
- **Jetpack Compose + Material 3** UI
- ViewModel + StateFlow + SharedFlow（面向状态架构）
- kotlinx-serialization（JSON 解析）
- restic 0.17+ 二进制（编译为 `librestic.so`）
- sardine-android (WebDAV 客户端)
- jcifs-ng (SMB 客户端)
- NanoHTTPD（REST 桥服务器）
- libsu（Magisk / KernelSU / APatch root shell）
- Kotest + MockK（单元测试）

## 架构

```
┌─────────────────────────────────────────────┐
│  UI 层 (Jetpack Compose + Material 3)       │
│  AppScaffold → BackupScreen / RestoreScreen │
│              / ConfigScreen                  │
│              / ConfigViewModel (StateFlow)   │
│              / BackupViewModel (StateFlow)   │
│              / RestoreViewModel (StateFlow)  │
├─────────────────────────────────────────────┤
│  业务逻辑层 (backup/)                        │
│  BackupOperation   → root shell tar/cp      │
│  RestoreOperation  → root shell pm install  │
│  ResticStreamBackup → 临时目录 → restic      │
│  ResticWrapper     → facade 委托给:          │
│    ├── ResticBackup      (备份)              │
│    ├── ResticRestore     (恢复 + dump)       │
│    ├── ResticSnapshotOps (快照列表/forget)    │
│    ├── ResticMaintenance (prune/check/stats) │
│    └── ResticRepoInit    (init)              │
│  ResticCommandRunner → ProcessBuilder       │
│  ResticEnvResolver   → 环境变量构建           │
│  RestBridgeRunner    → ResticRestBridge      │
│  RemoteTransport     → file I/O 接口          │
│    ├── WebdavTransport (sardine, 8KB 分块)    │
│    └── SmbTransport (jcifs-ng, 8KB 分块)      │
├─────────────────────────────────────────────┤
│  Root 层 (root/)                             │
│  RootShell → libsu (Magisk/KernelSU/APatch)  │
├─────────────────────────────────────────────┤
│  Service 层                                  │
│  BackupService → Foreground Service          │
├─────────────────────────────────────────────┤
│  原生二进制 (jniLibs)                          │
│  librestic.so / libtar_bin.so / libzstd_bin  │
└─────────────────────────────────────────────┘
```

### 数据流（一次备份）

```
用户选择应用 → 扫描 (AppScanner.enumerateUsers / scanThirdParty)
    ↓
创建 Backup_ 目录 → 写入 appList.txt + app_details.json
    ↓
并行 (Semaphore=3) 为每个应用:
    ├── 备份 APK (cp → app目录/包名.apk)
    ├── 备份数据 (tar zstd → 包名_data.tar.zst)
    ├── 备份 OBB (tar → 包名_obb.tar.zst)
    ├── 备份 SSAID (提取 → ssaid.txt)
    ├── 备份图标 (snapshot cache / aapt)
    └── 备份权限 (dumpsys → permissions.txt)
    ↓
WiFi 备份 → WifiManager.backup()
    ↓
(可选) ResticWrapper.backup() → restic 快照到远程仓库
```

### 远程仓库（REST 桥模式）

```
Restic CLI ←→ ResticRestBridge (NanoHTTPD, 127.0.0.1:random)
                      ↓
              RemoteTransport (SMB/WebDAV)
```

restic 通过 REST HTTP API 与本地桥通信，桥接器将请求翻译为 SMB/WebDAV 文件操作。
无需本地 staging 仓库，restic 直接读写远程存储。

## 构建

### 版本历史

| 版本 | 更新内容 |
|------|---------|
| v1.17 | 安全修复：root 注入防护、路径穿越防护、网络默认安全、凭据加密存储、任务取消 |
| v1.14 | 修复 shell 转义/管道死锁/配置序列化缺陷，新增配置导出与 BackupConfig 单元测试 |
| v1.13 | Compose Material 3 UI 重构、Unlock 支持、ResticBinary 启动初始化、修复 500 错误和刷新竞态 |
| v1.12 | 引擎 + Compose Material 3 UI 重构 |
| v1.11 | 构建系统改进、LSP 支持 |
| v1.10 | 后端桥接稳定性提升 |
| v1.9 | 远程后端优化 |
| v1.8 | 快照管理增强 |
| v1.7 | 多用户支持 |
| v1.6 | 累积快照 |
| v1.5 | 修复签名配置 |
| v1.4 | APK 体积优化（R8 + shrinkResources），25MB → 11.8MB |
| v1.3 | 累积快照、AppResult 类型化错误、kotlinx-serialization |

### 编译命令

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需配置签名）
KEYSTORE_PASSWORD=<密码> KEY_PASSWORD=<密码> ./gradlew assembleRelease
```

> Release 构建需要 `app/release.keystore`；原生库放在 `jniLibs/arm64-v8a/`。
> Release 构建必须提供签名配置，否则构建失败。

## 使用说明

1. Android 设备需 **root 权限**（Magisk / KernelSU / APatch）
2. 在「配置」页签设置备份选项 + restic 仓库参数
3. 切换「备份用户」选项（多用户设备）
4. 在「备份」页签选择应用 → 开始备份
5. 在「恢复」页签选择本地备份或 restic 快照 → 开始恢复

### 远程后端配置

| 字段 | WebDAV 示例 | SMB 示例 |
|------|-------------|----------|
| 后端 | WebDAV | SMB |
| 地址 | `https://webdav.example.com` | `192.168.1.165` |
| 用户名 | 账号 | Windows 用户名 |
| 密码 | 密码 | Windows 密码 |
| 共享名称 | — | `back` |
| 仓库存放路径 | `backup` | `backup` |

### 安全说明

- WebDAV 默认要求 HTTPS。HTTP 连接默认被拒绝。
- SMB 默认开启签名（signing），降级需要显式配置。
- 密码存储在 EncryptedSharedPreferences 中，不会明文写入配置文件。
- 备份和恢复支持从 UI 和通知栏取消。

### 注意事项

- 应用卸载会清除 `backup_settings.conf`，建议定期导出配置
- Restic 仓库需先「初始化」才能使用（自动检测已有仓库）
- SMB 密码错误多次会导致 Windows 账户锁定，需在服务器上解锁
- 实验性 Restic 临时目录备份不包含 OBB、外部数据、权限、SSAID、Wi-Fi
