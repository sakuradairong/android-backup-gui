# 修复报告：阶段 1、2、3

## 修复概述

根据 `ROOT_BACKUP_RESTORE_FIX_PLAN.md` 文档，已完成阶段 1、2、3 的核心修复。这些修复主要针对 root 权限下的安全风险、备份正确性和恢复流程的用户体验。

## 阶段 1：阻断 Root 注入和路径穿越 ✅

### 修复内容

1. **包名校验** (`RestoreOperation.kt`)
   - 使用 `PackageName.safe()` 过滤来自 `appList.txt` 和备份目录的包名
   - 拒绝非法包名（如 `../evil`、包含特殊字符的包名）

2. **路径穿越防护** (`RestoreOperation.kt`)
   - 对 `File(backupDir, pkg)` 做 `canonicalFile` 校验
   - 确保目标目录仍在 `backupDir` 内，防止路径逃逸

3. **APK 文件名过滤** (`RestoreApkInstaller.kt`)
   - APK 文件名只允许普通文件名
   - 拒绝包含 `/`、`\`、`.`、`..` 的文件名

4. **Shell 注入防护** (`RestoreApkInstaller.kt`)
   - `pm install -r -t $apkPaths` 中每个 APK 路径都加了单引号并 `shellEscape()`
   - 防止恶意文件名进入可执行 shell 语义

5. **归档安全检查增强** (`RestoreArchiveSafety.kt`)
   - 不仅拒绝绝对路径和 `..`，还拒绝 `etc/passwd` 这类相对路径
   - 所有条目必须落在调用方允许的目标前缀内
   - 归档恢复白名单限定到当前 package 目录

6. **压缩方式白名单** (`BackupConfig.kt`, `ConfigScreen.kt`)
   - 压缩方式从自由文本改为 allowlist
   - 只接受 `zstd` 或 `tar`，其他值归一为安全默认值
   - 防止 `Compression_method=';reboot;'` 进入目录名或 shell 命令

### 验收标准

- ✅ 恶意 `appList.txt` 中的 `../evil` 被过滤
- ✅ 恶意 APK 名 `a.apk; reboot` 不会进入可执行 shell 语义
- ✅ 恶意 tar entry `etc/hosts`、`/system/bin/x`、`../x` 全部拒绝
- ✅ `Compression_method=';reboot;'` 不会进入目录名或 shell 命令

## 阶段 2：修复备份正确性和失败统计 ✅

### 修复内容

1. **删除错误的增量跳过逻辑** (`BackupOperation.kt`)
   - 删除了"APK 未变就按旧 metadata 跳过 app data"的逻辑
   - 应用数据变化不依赖 APK version，旧逻辑会导致数据丢失

2. **APK 复制失败计数** (`BackupOperation.kt`)
   - APK copy 失败现在计入失败，不再只 log warning
   - 确保 `successCount` 准确反映实际成功的备份

3. **tar 参数顺序修正** (`BackupAppDataOps.kt`)
   - gzip/tar 参数顺序修正，确保 `-f` 后面紧跟 archive path
   - 修复了 `tar -czf $excludeArgs '$outputFile.gz'` 的错误顺序

4. **权限收紧** (`BackupOperation.kt`)
   - `chmod -R 0755` 改为 `chmod -R go-rwx`
   - 备份目录不再给 group/other 读权限

### 验收标准

- ✅ APK 复制失败时 `successCount` 不增加
- ✅ gzip 模式实际生成可校验归档
- ✅ 旧 metadata 存在时仍会重新备份 app data
- ✅ 备份目录权限收紧，不再暴露给其他应用

## 阶段 3：恢复流程安全 UX ✅

### 修复内容

1. **默认不全选应用** (`RestoreScreen.kt`)
   - 选择备份源后默认不全选应用
   - 防止用户误操作恢复所有应用

2. **提供明确的选择控制** (`RestoreScreen.kt`)
   - 提供"全选应用"和"取消全选"按钮
   - 用户可以精确控制要恢复的应用

3. **恢复确认弹窗** (`RestoreScreen.kt`)
   - 恢复前必须弹确认框
   - 显示应用数量、备份源、目标 userId、是否恢复 Wi-Fi
   - 明确警告覆盖数据风险

4. **Wi-Fi 恢复 opt-in** (`RestoreScreen.kt`)
   - Wi-Fi 恢复改为单独 opt-in，默认关闭
   - 恢复结果必须展示 Wi-Fi 成功/失败

5. **失败终态显示** (`ProgressBlock.kt`)
   - `partial` 或失败终态保持 error 色
   - 不在 `isRunning=false` 后变灰

### 验收标准

- ✅ 用户必须至少做一次明确选择才可恢复
- ✅ 点击恢复前会看到覆盖风险确认
- ✅ Wi-Fi 不会在用户未勾选时恢复
- ✅ 部分失败状态在完成后仍明显可见

## 测试验证

### 编译测试
```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```
结果：✅ BUILD SUCCESSFUL

### 单元测试
所有现有单元测试通过，包括：
- `PackageNameTest` - 包名校验
- `RestoreArchiveSafetyTest` - 归档安全检查
- `BackupConfigTest` - 配置解析

### Lint 检查
- ✅ 无新增错误
- ⚠️ 有一些 deprecation warnings（与本次修复无关）

## 修改文件列表

1. `app/src/main/java/com/example/androidbackupgui/backup/BackupAppDataOps.kt`
2. `app/src/main/java/com/example/androidbackupgui/backup/BackupConfig.kt`
3. `app/src/main/java/com/example/androidbackupgui/backup/BackupOperation.kt`
4. `app/src/main/java/com/example/androidbackupgui/backup/RestoreApkInstaller.kt`
5. `app/src/main/java/com/example/androidbackupgui/backup/RestoreAppDataOps.kt`
6. `app/src/main/java/com/example/androidbackupgui/backup/RestoreArchiveSafety.kt`
7. `app/src/main/java/com/example/androidbackupgui/backup/RestoreOperation.kt`
8. `app/src/main/java/com/example/androidbackupgui/ui/ConfigScreen.kt`
9. `app/src/main/java/com/example/androidbackupgui/ui/ProgressBlock.kt`
10. `app/src/main/java/com/example/androidbackupgui/ui/RestoreScreen.kt`

## 未完成的修复（后续阶段）

### 阶段 4：任务生命周期与取消（P1）
- Restore 逻辑从 `rememberCoroutineScope()` 移到 ViewModel
- 长任务状态通过 `StateFlow` 暴露
- Foreground service 支持 backup 和 restore 两类任务
- 通知栏增加取消 action
- UI 增加取消按钮

### 阶段 5：凭据与网络安全（P1）
- 默认禁止 cleartext traffic
- WebDAV 默认要求 `https://`
- 旧版明文密码迁移到 `PasswordManager`
- 禁止日志输出敏感信息

### 阶段 6：Restic streaming 策略（P2）
- 隐藏或禁用 streaming，或明确标注为实验功能

### 阶段 7：发布与仓库治理（P2）
- 从 git 移除 `app/release/*.apk`
- release build 缺签名配置时 fail
- 启用 R8/minify/shrinkResources
- 添加 CI

## 结论

本次修复成功完成了阶段 1、2、3 的核心内容，显著提升了应用的安全性：

1. **安全性提升**：阻断了 root 注入和路径穿越攻击
2. **可靠性提升**：修复了备份正确性问题，失败统计更准确
3. **用户体验提升**：恢复流程更安全，防止误操作

建议后续继续实施阶段 4-7 的修复，进一步提升应用的完整性和可维护性。
