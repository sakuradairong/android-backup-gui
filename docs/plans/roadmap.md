# Android Backup GUI — 后续路线图

## 已完成（当前版本）

| 领域 | 变更 | 阶段 |
|------|------|------|
| 🔒 安全 | 配置文件权限加固、签名密码加固 | P1 |
| 🔒 安全 | SMB MD4/AESCMAC 算法注入修复 + 全局注册 | Hotfix |
| 🐛 正确性 | `CancellationException` 透传 × 8 处 | P2 |
| 🐛 正确性 | SMB/WebDAV 返回 `Failure` 而非 `Success` | P2 |
| 🐛 正确性 | `BackupOperation.backupUserData` 全失败返回 `false` | P2 |
| 🐛 正确性 | `RestoreOperation` 改用 `supervisorScope` | P2 |
| 🐛 正确性 | `ResticCommandRunner` NPE 修复（2 处 readLine 模式） | Hotfix |
| 🌐 网络 | SMB/WebDAV 下载/上传自动重试 3 次 + 指数退避 | Hotfix |
| 🌐 网络 | WebDAV Range 断点续传（`.part` 文件 + HTTP Range） | Hotfix |
| 🏗️ 构建 | ResticRestBridge 绑定 127.0.0.1 | P3 |
| 🏗️ 构建 | `allowBackup=false` | P3 |
| 🏗️ 构建 | CI 添加 test 步骤 | P3 |
| 🧹 清理 | 删除 `MD4Provider.kt`、3 个死方法、`DataSizes`、`isFileNotFound`、`getAppLabel` | P4 |

---

## 下一阶段规划

### Phase A: 稳定性与恢复可靠性（3-5 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| A1 | 恢复操作 Fragment 修复 | `RestoreFragment.kt` | 添加 `onDestroyView` 防止视图分离后更新 UI | 低 |
| A2 | BackupFragment 修复 | `BackupFragment.kt` | 添加 `onDestroyView`，清理协程 | 低 |
| A3 | ResticRestBridge 认证 | `ResticRestBridge.kt` | 添加 token 认证，防止端口暴露 | 低 |
| A4 | WebDAV 超时可配置 | `WebdavTransport.kt` | Sardine 连接/读取超时通过构造参数设置 | 低 |
| A5 | tar 路径遍历检查 | `SELinuxUtil.kt` | `isArchiveSafe` 添加绝对路径检查 | 低 |
| A6 | 恢复后缓存清理 | `ResticRestBridge.kt` | restore 完成后清理 `restic_blob_*` 缓存文件 | 低 |

### Phase B: 遗留死代码与重构（2-3 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| B1 | 冗余导入清理 | 7 个文件 | 同包 `import` 冗余 | 低 |
| B2 | 未使用导入清理 | 5 个文件 | 删除无引用 import | 低 |
| B3 | 未使用参数清理 | 3 个函数 | 删除 `@Suppress("UNUSED_PARAMETER")` | 低 |
| B4 | TAG 修复 | `ResticRepoInit.kt`, `ResticCommandRunner.kt` | TAG 变量改为类名 | 低 |
| B5 | UID 解析提取 | `BackupOperation.kt`, `StreamingBackup.kt` | 重复的 UID 解析逻辑提取公共函数 | 低 |
| B6 | 5 个子模块重复分支提取 | `ResticBackup.kt` 等 | if-else local/remote 分支模式提取公共执行函数 | 中 |

### Phase C: 功能增强（5-7 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| C1 | 多目录恢复选择 | `RestoreFragment.kt` | 让用户选择从哪个 snapshot 恢复哪些目录 | 中 |
| C2 | 前台服务 | `BackupService.kt` | 备份/恢复时启动前台服务防止杀进程 | 中 |
| C3 | 多用户支持 | 全局 | `userId` 参数全面传递到 restore 流程 | 中 |
| C4 | 恢复进度细化 | `RestoreOperation.kt` | 每 blob 粒度进度回调 | 低 |

### Phase D: 安全加固（3-4 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| D1 | 密码加密存储 | `BackupConfig.kt` | EncryptedSharedPreferences + 迁移现有配置 | 中 |
| D2 | 仓库密码 UI 掩码 | `ConfigFragment.kt` | 确认/二次输入 | 低 |

### Phase E: 类型安全（大重构，2-3 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| E1 | `PackageName` 全面采用 | 8+ 文件 | 函数参数 `String` → `PackageName` | 高 |
| E2 | `UserId` 全面采用 | 8+ 文件 | 函数参数 `String`/`Int` → `UserId` | 高 |

### Phase F: i18n 国际化（2-3 天）

| # | 工作 | 文件 | 说明 | 风险 |
|---|------|------|------|------|
| F1 | strings.xml 提取 | 所有 UI 文件 | 将硬编码中文提取到 strings.xml | 低 |
| F2 | en/ 翻译 | strings.xml | 英文 strings.xml | 低 |

---

## 建议执行顺序

1. **Phase A**（稳定性优先 — 当前测试中暴露的问题优先修）
2. **Phase B**（清理干净再动大重构）
3. **Phase C**（用户可见功能）
4. **Phase D**（安全加固）
5. **Phase E**（类型安全 — 大重构，和 C 可能有冲突）
6. **Phase F**（最后做，纯文案）

**Phase A + B 可并行执行**。
