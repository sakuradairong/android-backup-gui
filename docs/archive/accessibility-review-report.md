# 无障碍审查报告 — Android Backup GUI

**审查阶段**：第三层 — 可维护性与用户体验  
**审查技能**：accessibility (WCAG 2.2)  
**审查日期**：2026-06-06  
**项目规模**：37 个源文件  
**审查范围**：UI 相关 4 个 Fragment/Activity、4 个布局 XML、菜单、资源文件  

---

## 严重程度说明

| 级别 | 定义 |
|------|------|
| **严重** | 用户无法完成核心操作，或屏幕阅读器完全无法识别交互元素 |
| **高** | 严重阻碍无障碍使用，有合理的替代方案但未实现 |
| **中** | 影响使用体验，但用户可通过变通方式完成任务 |
| **低** | 体验可改进点，非阻塞性 |

---

## 发现汇总

| # | 文件 | 行号 | 问题 | 严重程度 |
|---|------|------|------|----------|
| 1 | PackageListAdapter.kt | 61-69, 116 | `TextView` 模拟按钮(排除数据切换)缺少无障碍角色 | **严重** |
| 2 | PackageListAdapter.kt | 76-88 | `MaterialCardView` 点击区域未合并无障碍语义 | **高** |
| 3 | PackageListAdapter.kt | 52-53 | `CheckBox` 缺少对应应用的 `contentDescription` | **高** |
| 4 | PackageListAdapter.kt | 109-115 | 排除数据切换状态未以文字方式通知 | **中** |
| 5 | fragment_backup.xml | 168-169 | statusText 缺少无障碍实时区域 | **高** |
| 6 | fragment_restore.xml | 90-91 | statusText 缺少无障碍实时区域 | **高** |
| 7 | fragment_config.xml | 384-390 | configStatusText 缺少无障碍实时区域 | **高** |
| 8 | fragment_backup.xml | 152-160 | progressBar 开始/结束状态无无障碍通知 | **中** |
| 9 | fragment_restore.xml | 73-81 | progressBar 开始/结束状态无无障碍通知 | **中** |
| 10 | fragment_backup.xml | 100-133 | 排序/全选按钮文本仅 11sp，不符合可缩放要求 | **中** |
| 11 | fragment_backup.xml | 39-43 | "用户:" 标签与 Spinner 无程序化关联 | **中** |
| 12 | fragment_backup.xml | 59-65 | "输出目录:" 标签与目录显示无程序化关联 | **低** |
| 13 | fragment_restore.xml | 49-53 | "用户:" 标签与 Spinner 无程序化关联 | **中** |
| 14 | PackageListAdapter.kt | 61-69 | "数据"文本排除切换触摸目标可能不足 48dp | **中** |
| 15 | MainActivity.kt | 93-100 | BottomNavigation 缺少 `contentDescription` 仅凭图标导航 | **低** |

---

## 详细发现

### 发现 1：`TextView` 模拟按钮 — 排除数据切换(严重)

**文件**：`PackageListAdapter.kt`  
**行号**：61-69（创建），116（点击监听器）  
**问题**：使用 `TextView`（非标准按钮控件）实现点击交互。`TextView` 默认不向 TalkBack 宣告其可点击角色。屏幕阅读器用户听到"数据"但不知道可以点击切换。

```kotlin
// 第 61-69 行：创建
val et = TextView(ctx).apply {
    id = R.id.excludeToggle
    // ...
}

// 第 116 行：添加点击
toggle.setOnClickListener {
    dataToggleCb(pkg, !excluded)
}
```

**修复建议**：  
- 方案A(最优)：改用 `MaterialButton` 或 `ImageButton` 替代 `TextView`，并设置 `contentDescription`。  
- 方案B(最小改动)：在 `TextView` 上显式设置 `focusable = true`、`clickable = true`，并设置 `contentDescription` 说明其作用和状态。  
- 添加 `stateDescription` 或更新 `contentDescription` 反映当前切换状态（"点击排除数据备份" / "点击包含数据备份"）。

---

### 发现 2：卡片点击区域无障碍语义缺失(高)

**文件**：`PackageListAdapter.kt`  
**行号**：76-88  
**问题**：`MaterialCardView` 上设置了 `setOnClickListener` 用于切换 CheckBox，但 TalkBack 视卡片为一个独立可点击元素，未与内部 CheckBox 的角色合并。用户无法明确知道点击卡片的效果是切换选中状态。

```kotlin
// 第 76 行
card.setOnClickListener {
    val pos = holder.adapterPosition
    // ... 切换 checkbox
}
```

**修复建议**：  
- 为卡片设置 `contentDescription` 关联应用名和选中状态。  
- 或为卡片添加 `role = Role.Button` 的语义，合并内部子元素的无障碍信息。  
- 最佳实践是在 `onBindViewHolder` 中更新卡片的 `contentDescription` 为"勾选 ${app.label}"或"取消勾选 ${app.label}"。

---

### 发现 3：CheckBox 缺少对应描述(高)

**文件**：`PackageListAdapter.kt`  
**行号**：52-53（创建），92-102（绑定）  
**问题**：CheckBox 在代码中创建且未设置 `contentDescription`。虽然旁边有 `TextView` 显示应用名，但程序化关联不完善。

```kotlin
// 第 52-53 行
val cb = CheckBox(ctx).apply {
    id = R.id.checkbox
    // 没有 contentDescription
}
```

**修复建议**：在 `onBindViewHolder`（第 96 行设置 `textView.text` 之后）为 checkbox 设置 `contentDescription`：

```kotlin
holder.checkbox.contentDescription = "选择 ${app.label.ifEmpty { pkg }}"
```

当选中/取消时同步更新描述。

---

### 发现 4：排除数据切换状态缺少文字通知(中)

**文件**：`PackageListAdapter.kt`  
**行号**：109-115  
**问题**：排除数据开关通过 `paintFlags`（删除线）和 `isSelected` 来表示状态，这些仅视觉变化不会被 TalkBack 识别。用户无法知道当前是否已排除数据。

```kotlin
// 第 109-115 行
toggle.text = "数据"
toggle.paintFlags = if (excluded) {
    toggle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
} else {
    toggle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
}
toggle.isSelected = excluded
```

**修复建议**：显式更新 `contentDescription`：

```kotlin
toggle.contentDescription = if (excluded) {
    "排除 ${app.label.ifEmpty { pkg }} 的用户数据备份"
} else {
    "包含 ${app.label.ifEmpty { pkg }} 的用户数据备份"
}
```

---

### 发现 5/6/7：状态文字缺少无障碍实时区域(高)

**文件**：
- `fragment_backup.xml` 第 168-169 行（statusText）
- `fragment_restore.xml` 第 90-91 行（statusText）  
- `fragment_config.xml` 第 384-390 行（configStatusText）

**问题**：三个状态文字 View 均未设置 `accessibilityLiveRegion`。当代码调用 `updateStatus()` 或 `applyState()` 更新文本内容时，TalkBack 不会自动朗读变化。

**修复建议**：在布局 XML 中添加：
```xml
android:accessibilityLiveRegion="polite"
```

同时，建议在 `BackupFragment.kt:406` 的 `updateStatus` 方法和 `ConfigFragment.kt:136` 设置状态文本后手动触发无障碍事件，确保 TalkBack 播报：
```kotlin
binding.statusText.sendAccessibilityEvent(
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
)
```

---

### 发现 8/9：进度指示器状态无通知(中)

**文件**：
- `fragment_backup.xml` 第 152-160 行（progressBar）
- `fragment_restore.xml` 第 73-81 行（progressBar）

**问题**：`LinearProgressIndicator` 的 `visibility` 在 `VISIBLE`/`GONE` 之间切换（`BackupFragment.kt:402`、`RestoreFragment.kt:395`），但 TalkBack 不会主动通知用户加载开始或结束。

**修复建议**：在 `setRunning()` 方法中切换进度条可见性时，同步更新 `statusText` 并确保其 `accessibilityLiveRegion` 生效。例如在显示进度条时更新 statusText 为"正在加载…"，隐藏时更新为"加载完成"。

---

### 发现 10：排序/全选按钮文本过小(中)

**文件**：`fragment_backup.xml`  
**行号**：100-133  
**问题**：排序和全选按钮的 `android:textSize` 设置为 `11sp`。

```xml
<Button android:textSize="11sp" ... />
```

`11sp` 远低于 Android 推荐的 `14sp` 最小可读文本大小。当用户开启大字模式时，文本可读性受影响。此外按钮使用 `layout_weight="1"` 分布宽度，在窄屏上按钮宽度可能不足 48dp。

**修复建议**：  
- 将文本大小提升至 `14sp` 以上。  
- 添加 `android:minWidth="48dp"` 确保触摸区域不小于 48dp。  
- 考虑使用图标+文字的紧凑布局替代纯文字小按钮。

---

### 发现 11/13："用户:" 标签缺少程序化关联(中)

**文件**：
- `fragment_backup.xml` 第 39-43 行
- `fragment_restore.xml` 第 49-53 行

**问题**："用户:" 是一个独立的 `TextView`，与后面的 `Spinner` 无程序化关联。TalkBack 用户需自行推断两者关系。

**修复建议**：为 `TextView` 添加 `android:labelFor="@id/userSelector"` 属性：
```xml
<TextView
    android:labelFor="@+id/userSelector"
    ... />
```

---

### 发现 12："输出目录:" 标签缺少程序化关联(低)

**文件**：`fragment_backup.xml`  
**行号**：59-65  

**问题**："输出目录:" 标签之后是 `outputPathLabel`（显示路径的 TextView）和"修改"按钮。标签与路径显示之间无程序化关联。

**修复建议**：为输出目录标签添加 `labelFor` 指向 `outputPathLabel`，或将其合并为带标题的可操作区域。

---

### 发现 14：排除数据切换触摸目标可能不足 48dp(中)

**文件**：`PackageListAdapter.kt`  
**行号**：61-69  

**问题**："数据"文本是纯 `TextView`，没有设置 `minWidth` 或 `minHeight`。点击区域仅限于文本包裹范围，可能小于 Android 推荐的 48dp 最小触摸目标。

```kotlin
val et = TextView(ctx).apply {
    id = R.id.excludeToggle
    // 没有 minWidth/minHeight 或最小 padding 保证触摸区域
}
```

**修复建议**：添加最小触摸尺寸：
```kotlin
val et = TextView(ctx).apply {
    // ...
    minWidth = resources.getDimensionPixelSize(
        android.R.dimen.app_icon_size // 48dp
    )
    minimumHeight = resources.getDimensionPixelSize(
        android.R.dimen.app_icon_size
    )
}
```

或改用 `MaterialButton` 替代。

---

### 发现 15：BottomNavigation 缺少 ContentDescription(低)

**文件**：
- `MainActivity.kt` 第 93-100 行  
- `res/menu/bottom_nav.xml` 第 2-15 行

**问题**：BottomNavigationView 的菜单项包含 `android:title` 文本，但 `android:icon` 引用图标(`@drawable/ic_backup` 等)未设置 `android:contentDescription`。虽然 `android:title` 可被 TalkBack 读取，但图标作为装饰元素应标记为 `android:importantForAccessibility="no"` 以避免冗余播报。

**建议**：在菜单 XML 中为图标装饰属性添加声明（需在 menu 中设置 `app:iconContentDescription` 或在代码中设置）。不过由于 `labelVisibilityMode="labeled"`，title 总是可见的，TalkBack 可以通过 title 识别，此项优先级较低。

---

## 总结

### 按严重程度统计

| 严重程度 | 数量 |
|----------|------|
| 严重 | 1 |
| 高 | 4 |
| 中 | 8 |
| 低 | 2 |
| **总计** | **15** |

### 按文件分布

| 文件 | 发现问题数 | 最严重问题 |
|------|-----------|-----------|
| PackageListAdapter.kt | 5 | 严重 — TextView 模拟按钮 |
| fragment_backup.xml | 5 | 高 — 缺少无障碍实时区域 |
| fragment_restore.xml | 3 | 高 — 缺少无障碍实时区域 |
| fragment_config.xml | 1 | 高 — 缺少无障碍实时区域 |
| MainActivity.kt | 1 | 低 — BottomNavigation 图标描述 |

### 最优先修复项（共 5 项）

1. **PackageListAdapter.kt:61-69** — `TextView` 模拟按钮改为语义化按钮并添加 `contentDescription`
2. **PackageListAdapter.kt:76-88** — 卡片点击区域合并无障碍信息，添加选中/未选中的状态文字
3. **PackageListAdapter.kt:52-53** — CheckBox 绑定应用名作为 `contentDescription`
4. **fragment_backup.xml:168, fragment_restore.xml:90, fragment_config.xml:385** — 为所有状态文字添加 `accessibilityLiveRegion="polite"`
5. **PackageListAdapter.kt:109-115** — 排除切换状态同步到 `contentDescription`

### 项目整体无障碍评估

该应用大量使用 Material Design 3 标准组件，这些组件内置了基本无障碍支持（如 TalkBack 可识别 Button、Switch、CheckBox 等）。主要无障碍缺陷集中在**程序化创建的列表项**（`PackageListAdapter`）和**动态状态更新**缺乏通知机制。修复优先级建议从 PackageListAdapter 的 5 个问题开始，它们影响最核心的交互流程（应用选择和排除数据）。配置页面的无障碍支持较好（使用 TextInputLayout + hint 提供标签）。总体评分 **6/10**，完成上述修复后可提升至 **8/10**。
