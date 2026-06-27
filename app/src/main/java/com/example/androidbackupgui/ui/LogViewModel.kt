package com.example.androidbackupgui.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.androidbackupgui.backup.core.LogUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-visible state driven by [LogViewModel]. */
data class LogUiState(
    val logFiles: List<File> = emptyList(),
    val selectedFile: File? = null,
    val logContent: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

class LogViewModel(
    application: Application,
) : AndroidViewModel(application) {
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
            val lines = withContext(Dispatchers.IO) { file.readLines() }
            _uiState.update { it.copy(logContent = lines, isLoading = false) }
        }
    }

    fun deleteSelected() {
        val file = _uiState.value.selectedFile ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            refresh()
        }
    }

    fun exportToUri(uri: Uri, file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { `in` ->
                            `in`.copyTo(out)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LogViewModel", "导出日志失败", e)
                }
            }
        }
    }
}
