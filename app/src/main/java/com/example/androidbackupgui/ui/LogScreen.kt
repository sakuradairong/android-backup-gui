package com.example.androidbackupgui.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.androidbackupgui.backup.LogUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    val context = LocalContext.current
    var logFiles by remember { mutableStateOf(listOf<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var logContent by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Refresh log list
    fun refresh() {
        logFiles = LogUtil.getLogFiles()
        if (selectedFile != null && selectedFile !in logFiles) {
            selectedFile = null
            logContent = emptyList()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // SAF export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && selectedFile != null) {
            exportLogFile(context, uri, selectedFile!!)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ── Header ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("运行日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        if (logFiles.isEmpty()) {
            Text(
                "暂无日志文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }

        // ── Log file list ──
        Text("日志文件", style = MaterialTheme.typography.labelLarge)
        LazyColumn(
            modifier = Modifier.heightIn(max = 160.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logFiles, key = { it.absolutePath }) { file ->
                val isSelected = file == selectedFile
                Card(
                    onClick = {
                        selectedFile = file
                        scope.launch {
                            logContent = withContext(Dispatchers.IO) {
                                file.readLines()
                            }
                        }
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${file.length() / 1024}KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Action buttons ──
        if (selectedFile != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch(selectedFile!!.name) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出")
                }
                OutlinedButton(
                    onClick = {
                        selectedFile!!.delete()
                        refresh()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── Log content ──
            Text(
                "日志内容 — ${selectedFile!!.name}",
                style = MaterialTheme.typography.labelLarge
            )
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                if (logContent.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("（空）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        // Show last 500 lines (newest at bottom)
                        val displayLines = logContent.takeLast(500)
                        for (line in displayLines) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun exportLogFile(context: Context, uri: Uri, file: File) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { `in` ->
                `in`.copyTo(out)
            }
        }
    } catch (e: Exception) {
        Log.e("LogScreen", "导出日志失败", e)
    }
}
