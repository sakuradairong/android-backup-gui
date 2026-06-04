package com.example.androidbackupgui.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.BackupService
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.backup.RemoteTransport
import com.example.androidbackupgui.databinding.FragmentBackupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!
    private var apps: List<AppInfo> = emptyList()
    private var selectedApps = mutableSetOf<String>()
    private var sortedApps: List<AppInfo> = emptyList()
    private lateinit var config: BackupConfig
    private var selectedUserId: Int = 0
    private var userList: List<Pair<Int, String>> = listOf(0 to "Owner")
    private var sortMode: SortMode = SortMode.NAME_ASC
    private var showSystemApps: Boolean = false

    private enum class SortMode { NAME_ASC, SIZE_DESC }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val configFile = File(requireContext().filesDir, "backup_settings.conf")
        config = BackupConfig.fromFile(configFile)

        binding.appList.layoutManager = LinearLayoutManager(requireContext())

        binding.scanButton.setOnClickListener { scanApps() }
        binding.backupButton.setOnClickListener { startBackup() }

        // Sort/filter controls
        binding.sortAZButton.setOnClickListener {
            sortMode = SortMode.NAME_ASC
            applySortFilter()
        }
        binding.sortSizeButton.setOnClickListener {
            sortMode = SortMode.SIZE_DESC
            applySortFilter()
        }
        binding.selectAllButton.setOnClickListener {
            selectedApps.addAll(apps.map { it.packageName })
            applySortFilter()
        }
        binding.deselectAllButton.setOnClickListener {
            selectedApps.clear()
            applySortFilter()
        }
        binding.showSystemSwitch.setOnCheckedChangeListener { _, checked ->
            showSystemApps = checked
            applySortFilter()
        }

        // Load user profiles and setup dropdown
        loadUsers()
    }

    private fun loadUsers() {
        viewLifecycleOwner.lifecycleScope.launch {
            userList = AppScanner.enumerateUsers()
            val names = userList.map { (id, name) -> "$name (ID: $id)" }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.userSelector.adapter = adapter
            binding.userSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedUserId = userList.getOrNull(position)?.first ?: 0
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val configFile = File(requireContext().filesDir, "backup_settings.conf")
        config = BackupConfig.fromFile(configFile)
    }

    private fun scanApps() {
        binding.backupButton.isEnabled = false
        setRunning(true)
        binding.statusText.text = "正在扫描应用…"

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val thirdParty = AppScanner.scanThirdParty(ctx, userId = selectedUserId)
            val system = AppScanner.scanSystem(ctx, config, userId = selectedUserId)
            apps = if (showSystemApps) thirdParty + system else thirdParty
            selectedApps.clear()
            selectedApps.addAll(apps.map { it.packageName })

            binding.statusText.text = "共找到 ${apps.size} 个应用，全部已选中"
            binding.backupButton.isEnabled = apps.isNotEmpty()
            setRunning(false)

            applySortFilter()
        }
    }

    private fun applySortFilter() {
        var filtered = if (showSystemApps) apps else apps.filter { !it.isSystem }
        filtered = when (sortMode) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.label.lowercase(Locale.US) }
            SortMode.SIZE_DESC -> filtered.sortedByDescending { it.backupSize }
        }
        sortedApps = filtered
        setupAppList()
        binding.statusText.text = "已选择 ${selectedApps.size}/${sortedApps.size} 个应用"
    }

    private fun setupAppList() {
        val displayApps = sortedApps.ifEmpty { apps }
        binding.appList.adapter = PackageListAdapter(displayApps, selectedApps) { pkg, checked ->
            if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
            binding.statusText.text = "已选择 ${selectedApps.size}/${displayApps.size} 个应用"
        }
    }

    private fun startBackup() {
        val toBackup = apps.filter { it.packageName in selectedApps }
        if (toBackup.isEmpty()) return
        setRunning(true)
        binding.backupButton.isEnabled = false
        binding.scanButton.isEnabled = false

        // Start foreground service to keep process alive
        val serviceIntent = Intent(requireContext(), BackupService::class.java)
        serviceIntent.action = BackupService.ACTION_START_BACKUP
        serviceIntent.putExtra(BackupService.EXTRA_STATUS_TEXT, "正在备份 ${toBackup.size} 个应用…")
        try {
            requireContext().startForegroundService(serviceIntent)
        } catch (_: Exception) {}

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outputDir = File(config.outputPath.ifEmpty {
                    requireContext().filesDir.absolutePath
                })
                WifiManager.backup(outputDir)
                val result = BackupOperation.backupApps(
                    context = requireContext(),
                    apps = toBackup,
                    config = config,
                    outputDir = outputDir,
                    userId = selectedUserId.toString(),
                    onProgress = { progress ->
                        val label = toBackup.find { it.packageName == progress.packageName }?.label
                        val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                        binding.statusText.text =
                            "[${progress.current}/${progress.total}] $name: ${progress.message}"
                    }
                )

                // If restic is enabled, snapshot to repository
                var resticSummary: ResticWrapper.BackupSummary? = null
                var resticError: String? = null
                if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                    val binaryPath = ResticBinary.prepare(requireContext())
                    if (binaryPath != null) {
                        ResticWrapper.binaryPath = binaryPath
                        ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(requireContext())
                        ResticWrapper.backendDomain = config.resticBackendDomain

                        if (config.resticBackend == "local") {
                            if (!File(config.resticRepo, "config").exists()) {
                                binding.statusText.text = "restic 本地仓库未初始化，请先在设置中初始化"
                                return@launch
                            }
                        }
                        binding.statusText.text = "正在写入 restic 去重仓库…"
                        val resticResult = ResticWrapper.backup(
                            repoPath = config.resticRepo,
                            password = config.resticPassword,
                            paths = listOf(result.outputDir),
                            tags = listOf("backup_${System.currentTimeMillis() / 1000}"),
                            hostname = "android-backup-gui",
                            backend = config.resticBackend,
                            backendUrl = config.resticBackendUrl,
                            backendUser = config.resticBackendUser,
                            backendPass = config.resticBackendPass,
                            backendShare = config.resticBackendShare,
                            onSyncProgress = { progress: RemoteTransport.TransferProgress ->
                                if (progress.phase in listOf("list", "download", "upload", "delete_stale")) {
                                    updateStatus("同步中: ${progress.current}/${progress.total} 个文件")
                                }
                            },
                            onByteSyncProgress = { progress ->
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.max = progress.totalBytes.toInt().coerceAtLeast(1)
                                    binding.progressBar.progress = progress.bytesTransferred.toInt()
                                }
                                updateStatus("同步中: ${progress.currentFile}\n" +
                                    "${formatSize(progress.bytesTransferred)} / ${formatSize(progress.totalBytes)}")
                            },
                            onProgress = { progress ->
                                if (progress.messageType == "status") {
                                    updateStatus("去重仓库: %.0f%% (%d/%d 个文件)".format(
                                        progress.percentDone * 100,
                                        progress.filesDone,
                                        progress.totalFiles
                                    ))
                                }
                            }
                        )
                        when (resticResult) {
                            is AppResult.Success -> resticSummary = resticResult.data
                            is AppResult.Failure -> {
                                resticError = resticResult.error.message
                                updateStatus("restic 快照失败: ${resticResult.error.message}")
                        }
                    }
                }
                }

                updateStatus(buildString {
                    appendLine("备份完成！")
                    appendLine("成功: ${result.successCount}  失败: ${result.failCount}")
                    appendLine("耗时: ${result.elapsedMs / 1000}秒")
                    appendLine("输出: ${result.outputDir}")
                    val summary = resticSummary
                    if (summary != null) {
                        appendLine()
                        appendLine("── Restic 快照 ──")
                        appendLine("ID: ${summary.snapshotId.take(8)}…")
                        appendLine("新增: ${summary.dataAdded / 1024 / 1024} MB")
                        appendLine("文件: ${summary.totalFilesProcessed}")
                    } else {
                        val err = resticError
                        if (err != null) {
                            appendLine()
                            appendLine("── Restic 错误 ──")
                            appendLine(err)
                        }
                    }
                })
            } catch (e: Exception) {
                updateStatus("备份异常: ${e.message}")
            } finally {
                setRunning(false)
                binding.backupButton.isEnabled = true
                binding.scanButton.isEnabled = true
                // Stop foreground service
                try {
                    val stopIntent = Intent(requireContext(), BackupService::class.java)
                    stopIntent.action = BackupService.ACTION_STOP_BACKUP
                    requireContext().startService(stopIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun setRunning(running: Boolean) {
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
    }

    private suspend fun updateStatus(text: String) {
        withContext(Dispatchers.Main) { binding.statusText.text = text }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup restic temp files when leaving the fragment
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ResticWrapper.cleanup()
            }
        }
        _binding = null
    }
}
