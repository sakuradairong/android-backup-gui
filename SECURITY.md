# 安全策略

## 支持的版本

| 版本   | 支持状态          |
|--------|-------------------|
| v1.17  | ✅ 积极支持       |
| v1.14  | ✅ 积极支持       |
| v1.13  | ✅ 积极支持       |
| < v1.13| ❌ 不再支持       |

## 报告安全漏洞

如果您发现安全漏洞，**请不要在 GitHub Issues 中公开披露**。请通过以下方式私下报告：

1. 在仓库中创建一个 [Security Advisory](https://github.com/sakuradairong/android-backup-gui/security/advisories/new)
2. 或发送邮件至（待设置，目前请使用 Security Advisory）

我们会尽快确认并回应，通常在 **48 小时内**。

### 安全注意事项

- 本应用需要 root 权限运行，请确保从可信来源下载 APK
- 备份数据使用 restic 加密存储，请妥善保管仓库密码
- 如发现敏感信息泄露，请立即通过 Security Advisory 联系我们
- 密码存储在 Android EncryptedSharedPreferences 中，不会明文写入配置文件
- WebDAV 后端默认要求 HTTPS，HTTP 连接默认被拒绝
- SMB 默认开启签名（signing），降级需要显式配置
- 备份和恢复支持从 UI 和通知栏取消

### 发布产物校验

从 GitHub Release 下载 APK 后，请校验 SHA-256 以确保文件完整性：

```bash
sha256sum -c checksums.sha256
```

### Root 权限风险

本应用需要 root 权限，这意味着：

- 应用可以访问设备上的所有文件和数据
- 请确保设备已正确配置 root 权限（Magisk / KernelSU / APatch）
- 不要将 APK 分享给不受信任的用户
- 备份文件包含敏感数据，请妥善保管
