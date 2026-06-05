package com.example.androidbackupgui.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.BackupService
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.backup.AppResult
import com.example.androidbackupgui.databinding.FragmentBackupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import android.os.StatFs
import com.example.androidbackupgui.backup.StreamingBackup
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.root.shellEscape
import com.example.androidbackupgui.backup.formatSize
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
    private var excludeDataFromBackup = mutableSetOf<String>()

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
        updateOutputPathDisplay()

        binding.appList.layoutManager = LinearLayoutManager(requireContext())

        binding.scanButton.setOnClickListener { scanApps() }
        binding.outputPathEdit.setOnClickListener { showOutputPathEditDialog() }
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
            selectedApps.addAll(apps.map { it.packageName.value })
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
            try {
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
            } catch (e: Exception) {
                binding.statusText.text = "加载用户失败: ${e.message}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::config.isInitialized) {
            val configFile = File(requireContext().filesDir, "backup_settings.conf")
            config = BackupConfig.fromFile(configFile)
            updateOutputPathDisplay()
        }
    }

    private fun scanApps() {
        binding.backupButton.isEnabled = false
        setRunning(true)
        binding.statusText.text = "正在扫描应用…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val thirdParty = AppScanner.scanThirdParty(ctx, userId = selectedUserId)
                val system = AppScanner.scanSystem(ctx, config, userId = selectedUserId)
                apps = if (showSystemApps) thirdParty + system else thirdParty
                selectedApps.clear()
                selectedApps.addAll(apps.map { it.packageName.value })

                binding.statusText.text = "共找到 ${apps.size} 个应用，全部已选中"
                binding.backupButton.isEnabled = apps.isNotEmpty()
                setRunning(false)

                applySortFilter()
            } catch (e: Exception) {
                binding.statusText.text = "扫描应用失败: ${e.message}"
                setRunning(false)
                binding.backupButton.isEnabled = false
            }
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
        binding.appList.adapter = PackageListAdapter(
            displayApps, selectedApps,
            onToggle = { pkg, checked ->
                if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                binding.statusText.text = "已选择 ${selectedApps.size}/${displayApps.size} 个应用"
            },
            excludeDataFrom = excludeDataFromBackup,
            onExcludeDataToggle = { pkg, excluded ->
                if (excluded) excludeDataFromBackup.add(pkg) else excludeDataFromBackup.remove(pkg)
            }
        )
    }

    private fun startBackup() {
        val toBackup = apps.filter { it.packageName.value in selectedApps }
        if (toBackup.isEmpty()) return

        setRunning(true)
        binding.backupButton.isEnabled = false
        binding.scanButton.isEnabled = false

        // Start foreground service to keep process alive
        val serviceIntent = Intent(requireContext(), BackupService::class.java)
        serviceIntent.action = BackupService.ACTION_START_BACKUP
        serviceIntent.putExtra(BackupService.EXTRA_STATUS_TEXT, "正在备份 ${toBackup.size} 个应用…")
        try {
            ContextCompat.startForegroundService(requireContext(), serviceIntent)
        } catch (_: Exception) {}

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outputDir = File(config.outputPath.ifEmpty {
                    requireContext().filesDir.absolutePath
                })

                // ── Restic pre-flight: load snapshot metadata for cumulative merge ──
                var snapshotApps: Map<String, ResticWrapper.SnapshotAppInfo>? = null
                if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                    updateStatus("正在检查 restic 历史快照…")

                    if (config.resticBackend == "local" && !File(config.resticRepo, "config").exists()) {
                        updateStatus("restic 本地仓库未初始化，请先在设置中初始化")
                        return@launch
                    }

                    val binaryPath = ResticBinary.prepare(requireContext())
                    if (binaryPath != null) {
                        ResticWrapper.binaryPath = binaryPath
                        ResticWrapper.cacheDir = requireContext().cacheDir.absolutePath
                        ResticWrapper.backendDomain = config.resticBackendDomain

                        snapshotApps = ResticWrapper.getLatestSnapshotAppDetails(
                            repoPath = config.resticRepo,
                            password = config.resticPassword,
                            backend = config.resticBackend,
                            backendUrl = config.resticBackendUrl,
                            backendUser = config.resticBackendUser,
                            backendPass = config.resticBackendPass,
                            backendShare = config.resticBackendShare
                        )
                        if (snapshotApps != null) {
                            updateStatus("发现历史快照，将合并为累积备份")
                        }
                    }
                }

                // ── Build merged app list for cumulative snapshot ──
                val selectedPkgs = toBackup.map { it.packageName.value }.toSet()
                val allApps: List<AppInfo>
                val includePkgs: Set<String>

                if (snapshotApps != null) {
                    // Create placeholder AppInfo entries for packages from the snapshot
                    // that are NOT in the current selection. These won't be re-backed-up
                    // but their metadata is preserved via legacyApps.
                    val snapshotOnly = snapshotApps.keys.filter { it !in selectedPkgs }
                    val legacyEntries = snapshotOnly.mapNotNull { pkg ->
                        val snap = snapshotApps[pkg] ?: return@mapNotNull null
                        AppInfo(
                            packageName = PackageName(pkg),
                            label = snap.label,
                            isSystem = snap.isSystem
                        )
                    }
                    allApps = toBackup + legacyEntries
                    includePkgs = selectedPkgs
                    val snapCount = legacyEntries.size
                    if (snapCount > 0) {
                        updateStatus("累积备份: ${allApps.size} 个应用 ($snapCount 个来自历史快照)")
                    }

                    // Restore latest snapshot to populate directories for unchanged apps
                    updateStatus("正在恢复历史快照…")
                    val backupRoot = File(outputDir, "Backup_${config.compressionMethod}_${selectedUserId}")
                    backupRoot.mkdirs()
                    val snapsResult = ResticWrapper.listSnapshots(
                        repoPath = config.resticRepo,
                        password = config.resticPassword,
                        backend = config.resticBackend,
                        backendUrl = config.resticBackendUrl,
                        backendUser = config.resticBackendUser,
                        backendPass = config.resticBackendPass,
                        backendShare = config.resticBackendShare
                    )
                    val latestSnap = (snapsResult as? AppResult.Success)?.data?.firstOrNull()
                    if (latestSnap != null) {
                        ResticWrapper.restore(
                            repoPath = config.resticRepo,
                            password = config.resticPassword,
                            snapshotId = latestSnap.shortId,
                            targetPath = backupRoot.absolutePath,
                            backend = config.resticBackend,
                            backendUrl = config.resticBackendUrl,
                            backendUser = config.resticBackendUser,
                            backendPass = config.resticBackendPass,
                            backendShare = config.resticBackendShare
                        )
                    }
                } else {
                    allApps = toBackup
                    includePkgs = emptySet()
                }

                // ── Execute backup (with cumulative metadata) ──
                updateStatus("正在备份: ${allApps.size} 个应用…")
                val result = BackupOperation.backupApps(
                    context = requireContext(),
                    apps = allApps,
                    config = config,
                    outputDir = outputDir,
                    userId = selectedUserId.toString(),
                    noDataBackup = excludeDataFromBackup.toSet(),
                    includePkgs = includePkgs,
                    legacyApps = snapshotApps,
                    onProgress = { progress ->
                        val label = allApps.find { it.packageName.value == progress.packageName }?.label
                        val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                        updateStatus("[${progress.current}/${progress.total}] $name: ${progress.message}")
                    }
                )

                // Store WiFi config inside Backup_* directory so restic/local restore can find it
                WifiManager.backup(File(result.outputDir))

                // If restic is enabled, snapshot to repository
                var resticSummary: ResticWrapper.BackupSummary? = null
                var resticError: String? = null
                if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                    val binaryPath = ResticBinary.prepare(requireContext())
                    if (binaryPath != null) {
                        ResticWrapper.binaryPath = binaryPath
                        ResticWrapper.cacheDir = requireContext().cacheDir.absolutePath
                        ResticWrapper.backendDomain = config.resticBackendDomain

                        if (config.resticBackend == "local") {
                            if (!File(config.resticRepo, "config").exists()) {
                                updateStatus("restic 本地仓库未初始化，请先在设置中初始化")
                                return@launch
                            }
                        }
                        updateStatus("正在写入 restic 去重仓库…")
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
                    appendLine("模式: 累积快照")
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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


    private fun setRunning(running: Boolean) {
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
    }

    private suspend fun updateStatus(text: String) {
        withContext(Dispatchers.Main) { binding.statusText.text = text }
    }

    private fun updateOutputPathDisplay() {
        val path = config.outputPath.ifEmpty { requireContext().filesDir.absolutePath }
        binding.outputPathLabel.text = path
    }



    private fun showOutputPathEditDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(config.outputPath)
            hint = requireContext().filesDir.absolutePath
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改输出目录")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newPath = editText.text.toString().trim()
                config = config.copy(outputPath = newPath)
                BackupConfig.toFile(config, File(requireContext().filesDir, "backup_settings.conf"))
                updateOutputPathDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

        // ── Space detection & streaming backup ────────────

        /**
         * Estimate the total size of data to back up using `du -sb`.
         * Only counts data directories (not APKs) since that's the bulk.
         */
        private suspend fun estimateBackupSize(apps: List<com.example.androidbackupgui.backup.AppInfo>): Long = withContext(Dispatchers.IO) {
            var total = 0L
            for (app in apps) {
            val pkgEsc = app.packageName.value.shellEscape()
            val result = RootShell.exec("du -sb /data/data/$pkgEsc 2>/dev/null | cut -f1")
            val size = result.output.trim().toLongOrNull() ?: 0L
                total += size
            }
            total
        }

        /**
         * Check if [path] has at least [neededBytes] bytes free.
         * Uses [StatFs] to query the filesystem.
         */
        private fun hasEnoughSpace(path: File, neededBytes: Long): Boolean {
            try {
                val stat = StatFs(path.absolutePath)
                val available = stat.availableBlocksLong * stat.blockSizeLong
                // Require 1.5x headroom for temp files and metadata
                return available >= neededBytes * 3 / 2
            } catch (_: Exception) {
                // If we can't check, assume enough space (staging mode)
                return true
            }
        }

        /**
         * Run streaming backup via [StreamingBackup] + [ResticWrapper.backupStdin].
         * Used when staging space is insufficient.
         */
        @Suppress("UNUSED_PARAMETER")
        private suspend fun runStreamingResticBackup(
            config: com.example.androidbackupgui.backup.BackupConfig,
            apps: List<com.example.androidbackupgui.backup.AppInfo>,
            outputDir: File,
            cacheDir: String
        ): ResticWrapper.BackupSummary? {
            updateStatus("空间不足，启动流式备份模式…")

            val cacheDirFile = File(cacheDir, "streaming_tmp")
            cacheDirFile.mkdirs()

            // Prepare streaming: create FIFO, metadata, collect APK paths
            val streamingResult = StreamingBackup.prepareStreaming(
                cacheDirFile, apps, null
            )

            // Start restic with stdin from FIFO, in parallel with data producer
            var summary: ResticWrapper.BackupSummary? = null
            var backupError: String? = null

            coroutineScope {
                // Launch restic backup (consumer)
                val resticJob = async {
                    val result = ResticWrapper.backupStdin(
                        repoPath = config.resticRepo,
                        password = config.resticPassword,
                        stdinFile = streamingResult.dataFifo,
                        extraPaths = streamingResult.apkPaths + streamingResult.metaDir.absolutePath,
                        tags = listOf("streaming_${System.currentTimeMillis() / 1000}"),
                        hostname = "android-backup-gui",
                        backend = config.resticBackend,
                        backendUrl = config.resticBackendUrl,
                        backendUser = config.resticBackendUser,
                        backendPass = config.resticBackendPass,
                        backendShare = config.resticBackendShare,
                        onProgress = { progress ->
                            if (progress.messageType == "status") {
                                updateStatus("流式去重仓库: %.0f%% (%d/%d 个文件)".format(
                                    progress.percentDone * 100,
                                    progress.filesDone,
                                    progress.totalFiles
                                ))
                            }
                        }
                    )
                    when (result) {
                        is AppResult.Success -> summary = result.data
                        is AppResult.Failure -> backupError = result.error.message
                    }
                }

                // Launch data producer (writes tar to FIFO)
                val producerJob = async {
                    StreamingBackup.launchDataProducer(
                        apps = apps,
                        noDataBackup = excludeDataFromBackup.toSet(),
                        userId = selectedUserId.toString(),
                        fifoPath = streamingResult.dataFifo.absolutePath
                    )
                }

                // Wait for both to complete
                producerJob.await()
                resticJob.await()
            }

            // Cleanup FIFO
            try { streamingResult.dataFifo.delete() } catch (_: Exception) {}
            try { streamingResult.metaDir.deleteRecursively() } catch (_: Exception) {}

            if (backupError != null) {
                updateStatus("流式备份失败: $backupError")
            }
            return summary
        }
}
