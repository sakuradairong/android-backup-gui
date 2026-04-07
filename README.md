# Android Backup GUI

本项目为 backup_script 脚本提供 Android 图形化操作界面，支持本地运行脚本、参数配置、结果展示。

## 功能规划
- 通过界面选择/配置脚本参数
- 一键执行 backup_script/tools.sh
- 显示执行日志和结果

## 技术栈
- Kotlin
- Android Studio 项目结构

## 目录结构
- app/ 主要 Android 源码
- scripts/ 存放 shell 脚本（如 tools.sh）

## 使用说明
1. 用 Android Studio 打开本项目
2. 运行 app 模块
3. 在界面中配置参数并执行脚本

> 注意：需确保 Android 设备具备 shell 权限（如 root 或 Termux 环境），否则无法直接运行 shell 脚本。
