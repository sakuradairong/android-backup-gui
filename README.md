# Android Backup GUI

Android 应用备份与恢复工具，集成 [restic](https://restic.net/) 实现增量去重备份，支持 WebDAV / SMB 远程仓库。

## 功能

- **应用扫描** — 自动列出第三方应用，支持系统应用白名单
- **APK + 数据备份** — 备份 APK 文件、应用数据目录、OBB 数据、WiFi 配置
- **restic 增量去重** — 内建 `librestic.so`（~24MB），支持本地和远端仓库
- **远程后端** — WebDAV（如 123 云盘）/ SMB 协议，本地临时仓库 + 自动双向同步
- **配置持久化** — 仓库路径、密码、后端参数保存在 `backup_settings.conf`
- **快照管理** — 初始化仓库、查看统计、按策略清理旧快照（保留 7 天/4 周/3 月）
- **应用名显示** — 使用 `PackageManager` 解析应用名，优先显示中文名，回退包名

## 技术栈

- Kotlin / Android SDK
- restic 0.17+ 二进制（编译为 `librestic.so`）
- sardine-android (WebDAV 客户端)
- SMBJ (SMB 客户端)
- Coroutines + Dispatchers.IO
- Material 3 UI

## 架构

```
BackupFragment / RestoreFragment  (UI)
       │
       ▼
BackupOperation / RestoreOperation  (备份/恢复编排)
       │
       ├── AppScanner  (应用扫描、标签解析)
       ├── ResticWrapper  (restic CLI 封装)
       │     ├── RemoteTransport  (远程文件传输接口)
       │     │     ├── WebdavTransport  (sardine-android)
       │     │     └── SmbTransport  (SMBJ)
       │     └── ResticBinary  (librestic.so 路径管理)
       └── RootShell  (root 命令执行)
```

### restic 远程仓库流程

```
1. syncFromRemote: PROPFIND 递归列出远端文件 → 下载差异文件到本地临时仓库
2. runRestic: 在本地临时仓库执行 restic 命令 (backup / restore / snapshots)
3. syncToRemote: 递归遍历本地临时仓库 → 上传差异文件到远端
```

远端同步基于内容大小比较，跳过同名等长文件；自动删除远端/本地过时文件。

### 关键设计

- **href 自引用过滤** — WebDAV 服务器常将目录自身作为 PROPFIND 响应条目，通过比较资源 href 与请求 URL 精确过滤
- **根目录 404 保护** — 根目录返回 404 视为致命错误（防止限流导致误删本地文件），子目录 404 安全跳过
- **指数退避重试** — DNS 超时、5xx 错误、连接拒绝等瞬时故障自动重试（1s/2s/4s），最多 3 次
- **双向递归同步** — BFS 遍历远端目录树，深度限制 3 层，适配 restic 仓库结构
- **标签解析** — `PackageManager.getApplicationLabel()` 批量解析，无 root 需求，比 `dumpsys package` 快 10x+

## 编译

```bash
# Debug APK
./gradlew assembleDebug

# Release (需配置签名)
./gradlew assembleRelease
```

`librestic.so` 需放在 `app/src/main/jniLibs/arm64-v8a/` 目录下，在 `build.gradle` 中禁用 `extractNativeLibs` 前的 `useLegacyPackaging`。

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

