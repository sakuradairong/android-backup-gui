# 贡献指南

感谢您对 **Android Backup GUI** 的关注！欢迎通过 Issue 和 Pull Request 参与贡献。

## 目录

- [开发环境](#开发环境)
- [构建项目](#构建项目)
- [提交 Issue](#提交-issue)
- [提交 Pull Request](#提交-pull-request)
- [代码风格](#代码风格)

## 开发环境

- **JDK**: 17+
- **Android SDK**: API 34（targetSdk 34, minSdk 24）
- **IDE**: Android Studio Hedgehog+ 或 IntelliJ IDEA
- **Gradle**: 8.2（通过 Gradle Wrapper 自动使用）

### 首次构建

```bash
# 克隆仓库
git clone https://github.com/sakuradairong/android-backup-gui.git
cd android-backup-gui

# 确认 Android SDK 路径（创建 local.properties 如果不存在）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 构建 debug APK
./gradlew assembleDebug
```

## 构建项目

```bash
# 运行 lint 检查
./gradlew lint

# 运行单元测试
./gradlew test

# 构建 release APK（需配置签名）
./gradlew assembleRelease
```

> **注意**: Release 构建需要 `app/release.keystore` 文件，以及 `KEYSTORE_PASSWORD` 和 `KEY_PASSWORD` 环境变量。开发调试请使用 `assembleDebug`。

## 提交 Issue

### Bug 报告

请确保包含以下信息：

1. **设备信息**: Android 版本、设备型号、是否 root
2. **环境**: restic 版本（如有）、root 方案（Magisk / KernelSU / APatch）
3. **复现步骤**: 详细的操作步骤
4. **预期行为**: 您期望发生什么
5. **实际行为**: 实际发生了什么
6. **日志**: 相关的 logcat 输出或错误截图

### 功能请求

请说明：

1. **使用场景**: 这个功能解决什么问题
2. **期望方案**: 期望的行为或交互方式
3. **替代方案**: 您考虑过的其他方案

## 提交 Pull Request

1. **Fork** 本仓库
2. 创建功能分支: `git checkout -b feature/your-feature`
3. **确保代码通过 lint 和测试**:
   ```bash
   ./gradlew lint test
   ```
4. 提交变更:
   ```bash
   git commit -m "feat: 简洁描述变更内容"
   ```
5. 推送到您的 Fork: `git push origin feature/your-feature`
6. 创建 Pull Request 到 `main` 分支

### PR 要求

- 每个 PR 专注于一个功能或修复
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范
- 新增功能应包含对应的单元测试
- UI 变更请在 PR 描述中附上截图
- 确保 `./gradlew lint test` 通过

## 代码风格

- 使用 Kotlin 官方代码风格（Kotlin Coding Conventions）
- 使用 `ktlint` 检查代码格式（`./gradlew lint` 包含）
- compose 相关代码遵循 Jetpack Compose 编码规范
- 命名使用直观的英文（不推荐拼音）
- 对复杂逻辑编写简明注释

## 许可

贡献即表示您同意您的贡献将在 [GPL-3.0](LICENSE) 许可下发布。
