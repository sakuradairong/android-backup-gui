package com.example.androidbackupgui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 备份/恢复通用结构化进度展示组件，三态：
 *  - [isRunning] && [progressTotal] > 0：显示阶段名 + 计数 + 进度条 + 消息行
 *  - [isRunning] && 无结构化进度：圆形 spinner + [statusText]
 *  - !isRunning：仅显示 [statusText]
 *
 * 阶段名通过 [stageDisplayName] 映射，由调用方提供（备份/恢复各有自己的映射表，
 * 见 [backupStageDisplayName] / [restoreStageDisplayName]）。
 *
 * 失败语义：当 [progressStage] 为 "partial" 时进度条与计数使用 error 色，
 * 用于让用户在多个应用部分失败时立刻察觉（备份工具的关键诉求）。
 *
 * @param progressPercent 0.0~1.0 的确定百分比，null 表示按计数计算
 */
@Composable
fun ProgressBlock(
    isRunning: Boolean,
    statusText: String,
    progressCurrent: Int,
    progressTotal: Int,
    progressStage: String,
    progressPackageName: String,
    progressMessage: String,
    progressPercent: Float?,
    stageDisplayName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val isError = progressStage == "partial"
    if (isRunning && progressTotal > 0) {
        val counterColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val trackColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val computedFraction =
            (progressPercent ?: (progressCurrent.toFloat() / progressTotal.coerceAtLeast(1)))
                .coerceIn(0f, 1f)

        Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stageDisplayName(progressStage) +
                            if (progressPackageName.isNotEmpty()) " — $progressPackageName" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$progressCurrent/$progressTotal",
                    style = MaterialTheme.typography.labelSmall,
                    color = counterColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { computedFraction },
                color = trackColor,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            if (progressMessage.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = progressMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    } else if (isRunning) {
        Row(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** 备份阶段标识 → 用户友好中文名。pure function，便于单元测试。 */
fun backupStageDisplayName(stage: String): String =
    when (stage) {
        "apk" -> "备份 APK"
        "data" -> "备份数据"
        "obb" -> "备份 OBB"
        "ssaid" -> "备份 SSAID"
        "appdone" -> "已完成"
        "restic" -> "上传至 Restic"
        "done" -> "完成"
        "partial" -> "部分完成"
        else -> stage.ifEmpty { "处理中" }
    }

/** 恢复阶段标识 → 用户友好中文名。pure function，便于单元测试。 */
fun restoreStageDisplayName(stage: String): String =
    when (stage) {
        "install" -> "安装 APK"
        "data" -> "恢复数据"
        "obb" -> "恢复 OBB"
        "ssaid" -> "恢复 SSAID"
        "permissions" -> "恢复权限"
        "appdone" -> "已完成"
        "done" -> "完成"
        "partial" -> "部分完成"
        else -> stage.ifEmpty { "处理中" }
    }
