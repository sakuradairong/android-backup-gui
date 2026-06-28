package com.example.androidbackupgui.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.core.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI-visible state driven by [LogViewModel]. */
data class LogUiState(
    val logFiles: List<File> = emptyList(),
    val selectedFile: File? = null,
    val logContent: List<String> = emptyList(),
    val isLoading: Boolean = false,
    /** 最近一次导出操作的提示消息，null 表示无消息（审查报告 L7）。 */
    val exportMessage: String? = null,
)

class LogViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private companion object {
        /** 单文件在 UI 中最多加载的行数，防止超大日志文件导致 OOM（审查报告 M4）。 */
        const val MAX_LOG_LINES = 5000
    }

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val files = LogUtil.getLogFiles()
        _uiState.update { state ->
            val selected = state.selectedFile?.takeIf { it in files }
            state.copy(
                logFiles = files,
                selectedFile = selected,
                logContent = if (selected == null) emptyList() else state.logContent,
            )
        }
    }

    fun selectFile(file: File) {
        _uiState.update { it.copy(selectedFile = file, isLoading = true) }
        viewModelScope.launch {
            // 审查报告 M4：避免对超大的日志文件一次性 readLines() 引发 OOM。
            // 单次扫描统计总行数与前 MAX_LOG_LINES 行，超过则在尾部追加截断提示。
            val display =
                withContext(Dispatchers.IO) {
                    var count = 0
                    val acc = ArrayList<String>(MAX_LOG_LINES + 1)
                    file.useLines { seq ->
                        for (line in seq) {
                            count++
                            if (acc.size < MAX_LOG_LINES) acc.add(line)
                        }
                    }
                    if (count > acc.size) {
                        acc.add("…（日志过长，仅显示前 ${acc.size} 行，共 $count 行。完整内容请导出查看）")
                    }
                    acc
                }
            _uiState.update { it.copy(logContent = display, isLoading = false) }
        }
    }

    fun deleteSelected() {
        val file = _uiState.value.selectedFile ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            refresh()
        }
    }

    fun exportToUri(
        uri: Uri,
        file: File,
    ) {
        viewModelScope.launch {
            val message =
                withContext(Dispatchers.IO) {
                    try {
                        val written =
                            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { `in` ->
                                    `in`.copyTo(out)
                                }
                                true
                            } ?: false
                        // openOutputStream 返回 null 或复制异常时显式提示，不再静默失败（审查报告 L7）。
                        if (written) "导出成功：${file.name}" else "导出失败：无法打开目标文件"
                    } catch (e: Exception) {
                        Log.e("LogViewModel", "导出日志失败", e)
                        "导出失败：${e.message ?: "未知错误"}"
                    }
                }
            _uiState.update { it.copy(exportMessage = message) }
        }
    }

    /** 清除导出提示消息（UI 展示后调用）。 */
    fun clearExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }
}
