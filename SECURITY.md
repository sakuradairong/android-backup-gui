# 安全策略

## 支持的版本

| 版本   | 支持状态          |
|--------|-------------------|
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
