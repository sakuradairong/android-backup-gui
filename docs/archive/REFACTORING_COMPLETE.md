# 低耦合重构完成总结

## 概览

本文档记录 v1.18 周期完成的 8 个迭代低耦合重构。这些迭代专注于**结构性耦合**而非性能优化（性能优化已在 v1.13-v1.17 的 PHASE1-4 中完成）。

**核心成果**：通过引入 3 个抽象层（`BackupServiceBridge` / `ResticSessionFactory` / 多个工具类）和清理历史遗留的废弃 shim，将 ViewModel 与 Android framework、restic 业务、单例全局状态的耦合降至零。

## 8 个迭代的时间线

| 迭代 | 主题 | 新增抽象层 | 消除的耦合 |
|---|---|---|---|
| 1 | ViewModel ↔ Service 解耦 | `BackupServiceBridge` | ViewModel 直接构造 `Intent(BackupService::class)` |
| 2 | ViewModel ↔ Restic Session 解耦 | `ResticSessionFactory` | ViewModel 直接修改 `defaultResticWrapper` 可变属性 |
| 3 | JSON 解析从 ResticWrapper 剥离 | `AppDetailsParser` (core) | ViewModel 持有单例做纯 JSON 解析 |
| 4 | MainActivity bootstrap 解耦 | (复用 R2 抽象) | MainActivity 直接 mutation 单例 |
| 5 | URL 拼接工具提取（fresh scan 发现迭代 2 遗漏） | `RepoUrlBuilder` (core) | ConfigViewModel 持有单例做纯字符串拼接 |
| 6 | 修复 core/restic 反向依赖（fresh scan 发现迭代 3 遗漏） | `SnapshotAppInfo` (core) | `AppDetailsParser` 反向依赖 `ResticWrapper` 类型 |
| 7 | 外部调用方迁出 BackupOperation 废弃 shim | (无新抽象) | 外部代码持有 god-class 引用做纯文件 I/O |
| 8 | BackupOperation 内部迁移 + 完全删除废弃 shim | (无新抽象) | BackupOperation 内部自我代理 |

## 抽象层架构（最终）

```
backup/
├── BackupServiceBridge.kt            # Service Intent 通信抽象
│                                       ├── interface BackupServiceBridge
│                                       └── class AndroidBackupServiceBridge (impl)
│
├── core/                              # 零 restic 依赖的纯工具包
│   ├── AppDetailsParser.kt           # JSON 解析（独立工具）
│   ├── RepoUrlBuilder.kt             # URL 拼接工具
│   └── SnapshotAppInfo.kt            # 数据类（@Serializable，独立于 restic）
│
└── restic/
    ├── ResticSessionFactory.kt       # restic session 封装（独家掌控单例）
    │                                   ├── interface ResticSessionFactory
    │                                   └── class DefaultResticSessionFactory (impl)
    │
    └── ResticWrapper.kt              # 纯化后仅含 restic 业务方法（facade）
```

## 度量对比

| 指标 | 重构前 | 重构后 | 变化 |
|---|---|---|---|
| 测试总数 | 137 | 176 | +39 |
| 测试失败 | 0 | 0 | ✓ |
| 新增抽象层文件 | 0 | 7 | +7 |
| 新增测试文件 | 0 | 4 | +4 |
| BackupOperation.kt 行数 | 597 | 524 | -73 (-12.2%) |
| ViewModel/Activity 对 `defaultResticWrapper` 引用 | 多处 | 0 | 完全消除 |
| 外部代码对 `BackupOperation` 废弃 shim 引用 | 多处 | 0 | 完全消除 |
| `backup/core/` 对 `backup/restic/` 代码依赖 | (反依赖) | 0 | 反转 |

## 关键设计原则（已贯彻）

1. **接口隔离原则 (ISP)**：BackupServiceBridge / ResticSessionFactory 都是窄接口（3 个方法 / 1 个方法）
2. **依赖倒置原则 (DIP)**：ViewModel 与 MainActivity 都依赖抽象而非具体实现
3. **单一职责原则 (SRP)**：JSON 解析 / Service Intent / restic 配置 / URL 拼接 各自独立
4. **封装原则 (Encapsulation)**：`defaultResticWrapper` 可变属性由 ResticSessionFactory 独家掌控
5. **依赖方向原则**：`core/` 包零依赖 `restic/` 包；`restic/` 可依赖 `core/`

## 29 个 Scenarios 总数（已 grep 验证）

每个迭代验证 2-4 个 Scenarios：

| 迭代 | Scenarios |
|---|---|
| 1-2 | S1-S9: ViewModel 编译 + 既有测试 + grep 验证 |
| 3 | S10-S11: 新测试编译 + 全量测试通过 |
| 4 | S12-S17: MainActivity 解耦 + 工厂封装验证 |
| 5 | S18-S19: RepoUrlBuilder grep 验证 |
| 6 | S20-S23: SnapshotAppInfo 依赖方向修复 |
| 7 | S24-S25: 外部调用方迁出 BackupOperation 废弃 shim |
| 8 | S26-S29: BackupOperation 内部迁移 + 完全删除废弃 shim |

## 关键 Learnings（跨 8 个迭代）

1. **fresh scan 的纪律**：每次迭代后做 grep 扫描，能发现前次迭代的遗漏（迭代 5/6/7/8 都找到了之前的遗漏）
2. **抽象层一致性**：函数与类型必须一起迁移（迭代 6 修复了迭代 3 的 `AppDetailsParser` → `ResticWrapper.SnapshotAppInfo` 反向依赖）
3. **完成半完成状态**：分步完成历史遗留的重构（迭代 7 外部，迭代 8 内部）
4. **Kotlin 不支持嵌套 typealias**：发现后用直接 import 替代，反而强化了依赖方向（迭代 6）
5. **sed 词边界陷阱**：`\bword(` 在 `prefix.word(` 中仍会匹配，需注意（迭代 8）
6. **测试即文档**：characterization test 锁住原行为，防止未来意外修改（迭代 5 的 `//` 行为快照）
7. **死代码删除是低耦合的胜利**：13 个废弃函数删除同时实现行数减少、依赖简化、可读性提升（迭代 8）
8. **跨迭代的渐进改进**：每次迭代不只消除一个耦合点，还揭示新的耦合点

## 下一步建议

### 短期（低风险）
1. **拆分 BackupOperation.kt**：524 行仍是最大单一文件，但拆分需谨慎（其 `BackupResult`/`BackupProgressTracker` 紧密关联）
2. **为 ResticSessionFactory 添加 Robolectric 测试**：在真实 Android 设备上验证 wrapper 配置正确性

### 中期（需新依赖）
3. **引入 DI 框架（Hilt/Koin）**：当前手动构造参数已接近边界，3 个 ViewModel × 2 个可选构造参数 = 6 个参数
4. **替换 `defaultResticWrapper` 为 DI 容器管理**：消除最后的全局单例

### 长期（架构演进）
5. **重构 `core/` 为独立 module**：物理隔离 core 与 restic 的依赖关系
6. **拆分 `ConfigViewModel.kt` (807 行)**：最大 ViewModel，可按功能域拆分

## 变更文件清单

### 新增生产文件（7）
- `app/src/main/java/com/example/androidbackupgui/backup/BackupServiceBridge.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticSessionFactory.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/core/AppDetailsParser.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/core/RepoUrlBuilder.kt`
- `app/src/main/java/com/example/androidbackupgui/backup/core/SnapshotAppInfo.kt`

### 新增测试文件（4）
- `app/src/test/java/com/example/androidbackupgui/backup/BackupServiceBridgeTest.kt` (6 tests)
- `app/src/test/java/com/example/androidbackupgui/backup/restic/ResticSessionFactoryTest.kt` (5 tests)
- `app/src/test/java/com/example/androidbackupgui/backup/core/AppDetailsParserTest.kt` (12 tests)
- `app/src/test/java/com/example/androidbackupgui/backup/core/RepoUrlBuilderTest.kt` (16 tests)

### 修改生产文件（5）
- `app/src/main/java/com/example/androidbackupgui/ui/BackupViewModel.kt`（-4 / +198 行：注入抽象层 + 移除通配符）
- `app/src/main/java/com/example/androidbackupgui/ui/RestoreViewModel.kt`（+50 行：注入抽象层 + 移除通配符）
- `app/src/main/java/com/example/androidbackupgui/ui/ConfigViewModel.kt`（+15 行：注入抽象层）
- `app/src/main/java/com/example/androidbackupgui/MainActivity.kt`（用 ResticSessionFactory 替代直接 mutation）
- `app/src/main/java/com/example/androidbackupgui/backup/BackupOperation.kt`（-73 行：删除 13 个废弃 shim）
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticWrapper.kt`（parseAppDetailsJson + buildRepoUrl 改为 @Deprecated shim，最终由 core 工具取代）
- `app/src/main/java/com/example/androidbackupgui/backup/restic/ResticStreamBackup.kt`（迁出 BackupOperation 废弃 shim）
- `app/src/main/java/com/example/androidbackupgui/backup/RestoreOperation.kt`（迁出 BackupOperation 废弃 shim）

### 修改构建配置（1）
- `app/build.gradle`（新增 `testImplementation "org.json:json:20231013"`——测试用 org.json 真实实现）

## 结论

本次 8 个迭代的低耦合重构完成了**结构性清理**而非功能新增：
- 5 个 ViewModel/Activity 文件解耦（不再持有全局单例）
- core 包反转依赖方向（不再依赖 restic）
- 597 行 BackupOperation 缩减至 524 行（废弃 shim 完全清理）
- 新增 39 个测试覆盖新抽象层

整体测试从 137 → 176（+28.5%），0 失败 0 错误。重构成果在 CLAUDE.md 和本文件中记录，确保未来维护者能理解设计意图。
