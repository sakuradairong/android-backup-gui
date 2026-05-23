package com.example.androidbackupgui.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.RestoreOperation
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.databinding.FragmentRestoreBinding
import kotlinx.coroutines.launch
import java.io.File

class RestoreFragment : Fragment() {

    private var _binding: FragmentRestoreBinding? = null
    private val binding get() = _binding!!
    private var backupDir: File? = null
    private var packages: List<String> = emptyList()
    private var appInfos: List<AppInfo> = emptyList()
    private var selectedPackages = mutableSetOf<String>()
    private var resticConfig: BackupConfig? = null
    private var selectedSnapshot: ResticWrapper.ResticSnapshot? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRestoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.appList.layoutManager = LinearLayoutManager(requireContext())

        // Load restic config
        val configFile = File(requireContext().filesDir, "backup_settings.conf")
        val config = BackupConfig.fromFile(configFile)

        // Show restic button if enabled and binary available
        if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
            resticConfig = config
            val binaryPath = ResticBinary.prepare(requireContext())
            if (binaryPath != null) {
                ResticWrapper.binaryPath = binaryPath
                ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(requireContext())
                binding.selectResticButton.visibility = View.VISIBLE
            }
        }

        binding.selectDirButton.setOnClickListener { selectBackupDir() }
        binding.selectResticButton.setOnClickListener { selectResticSnapshot() }
        binding.restoreButton.setOnClickListener { startRestore() }
    }

    private fun selectBackupDir() {
        val defaultDir = File(requireContext().filesDir.absolutePath)
        val backupDirs = defaultDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("Backup_") }
            ?: emptyList()

        if (backupDirs.isNotEmpty()) {
            backupDir = backupDirs.first()
            selectedSnapshot = null
            loadBackupDir(backupDirs.first())
        } else {
            binding.statusText.text = "未找到備份目錄，請確保 Backup_* 資料夾存在於 ${defaultDir.absolutePath}"
        }
    }

    private fun loadBackupDir(dir: File) {
        binding.backupDirText.text = dir.absolutePath

        val appListFile = File(dir, "appList.txt")
        packages = if (appListFile.exists()) {
            appListFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            dir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        }

        selectedPackages.clear()
        selectedPackages.addAll(packages)

        // Resolve app labels for display
        appInfos = AppScanner.resolveLabels(requireContext(), packages.map { AppInfo(packageName = it) })

        binding.statusText.text = "共 ${packages.size} 個備份應用"
        binding.restoreButton.isEnabled = packages.isNotEmpty()

        setupAppList()
    }

    private fun selectResticSnapshot() {
        val config = resticConfig ?: return
        setRunning(true)
        binding.statusText.text = "正在讀取 restic 快照列表…"

        viewLifecycleOwner.lifecycleScope.launch {
            val snapshotsResult = ResticWrapper.listSnapshots(
                config.resticRepo, config.resticPassword,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass,
                backendShare = config.resticBackendShare
            )
            if (snapshotsResult.isFailure) {
                binding.statusText.text = "讀取快照失敗: ${snapshotsResult.exceptionOrNull()?.message}"
                setRunning(false)
                return@launch
            }

            val snapshots = snapshotsResult.getOrThrow()
            if (snapshots.isEmpty()) {
                binding.statusText.text = "沒有可用的 restic 快照"
                setRunning(false)
                return@launch
            }

            // Switch to restic source
            backupDir = null
            selectedSnapshot = snapshots.first()
            val backupPath = selectedSnapshot!!.paths.firstOrNull() ?: run {
                binding.statusText.text = "快照中找不到備份路徑"
                setRunning(false)
                return@launch
            }

            // Read app list from the snapshot
            val appListContent = readResticFile(config, selectedSnapshot!!.id, "$backupPath/appList.txt")
            packages = if (appListContent != null) {
                appListContent.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } else {
                emptyList()
            }

            if (packages.isEmpty()) {
                binding.statusText.text = "無法從快照讀取應用列表"
                setRunning(false)
                return@launch
            }

            binding.backupDirText.text = "restic: ${selectedSnapshot!!.time.take(19)} (${snapshots.size} 個快照可用)"
            selectedPackages.clear()
            selectedPackages.addAll(packages)

            // Resolve app labels for display
            appInfos = AppScanner.resolveLabels(requireContext(), packages.map { AppInfo(packageName = it) })

            binding.statusText.text = "restic 快照共 ${packages.size} 個應用，點擊恢復開始"
            binding.restoreButton.isEnabled = true
            setRunning(false)
            setupAppList()
        }
    }

    /** Read a single file from a restic snapshot using `restic dump`. */
    private suspend fun readResticFile(
        config: BackupConfig,
        snapshotId: String,
        filePath: String
    ): String? {
        val result = ResticWrapper.dump(
            config.resticRepo, config.resticPassword,
            snapshotId, filePath,
            backend = config.resticBackend,
            backendUrl = config.resticBackendUrl,
            backendUser = config.resticBackendUser,
            backendPass = config.resticBackendPass,
            backendShare = config.resticBackendShare
        )
        return result.getOrNull()
    }

    private fun setupAppList() {
        binding.appList.adapter = PackageListAdapter(appInfos, selectedPackages) { pkg, checked ->
            if (checked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
            binding.statusText.text = "已選擇 ${selectedPackages.size}/${packages.size} 個應用"
        }
    }

    private fun startRestore() {
        val toRestore = packages.filter { it in selectedPackages }
        if (toRestore.isEmpty()) return

        setRunning(true)
        binding.restoreButton.isEnabled = false
        binding.selectDirButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (selectedSnapshot != null && resticConfig != null) {
                // Restic restore
                val snapshot = selectedSnapshot!!
                val config = resticConfig!!
                val backupPath = snapshot.paths.firstOrNull() ?: return@launch

                val staging = File(requireContext().cacheDir, "restic_restore_${snapshot.shortId}")
                staging.mkdirs()

                binding.statusText.text = "正在從 restic 快照恢復到暫存目錄…"
                val restoreResult = ResticWrapper.restore(
                    repoPath = config.resticRepo,
                    password = config.resticPassword,
                    snapshotId = snapshot.id,
                    targetPath = staging.absolutePath,
                    backend = config.resticBackend,
                    backendUrl = config.resticBackendUrl,
                    backendUser = config.resticBackendUser,
                    backendPass = config.resticBackendPass,
                    backendShare = config.resticBackendShare,
                    onProgress = { msg -> binding.statusText.text = msg }
                )

                if (restoreResult.isFailure) {
                    binding.statusText.text = "restic 恢復失敗: ${restoreResult.exceptionOrNull()?.message}"
                    setRunning(false)
                    binding.selectDirButton.isEnabled = true
                    return@launch
                }

                // The restored backup directory: <staging>/<original_absolute_path>
                val restoredBackupDir = File(staging, backupPath.removePrefix("/"))
                binding.statusText.text = "正在從恢復的備份安裝應用…"

                val r = RestoreOperation.restoreApps(
                    backupDir = restoredBackupDir,
                    filterPkgs = selectedPackages,
                    onProgress = { progress ->
                        val label = appInfos.find { it.packageName == progress.packageName }?.label
                        val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                        binding.statusText.text =
                            "[${progress.current}/${progress.total}] $name: ${progress.message}"
                    }
                )
                // Cleanup staging
                try { staging.deleteRecursively() } catch (_: Exception) {}
                r
            } else {
                // Local restore
                val dir = backupDir ?: return@launch
                val r = RestoreOperation.restoreApps(
                    backupDir = dir,
                    filterPkgs = selectedPackages,
                    onProgress = { progress ->
                        val label = appInfos.find { it.packageName == progress.packageName }?.label
                        val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                        binding.statusText.text =
                            "[${progress.current}/${progress.total}] $name: ${progress.message}"
                    }
                )
                // Also restore WiFi if backup exists locally
                WifiManager.restore(dir)
                r
            }

            binding.statusText.text = buildString {
                appendLine("恢復完成！")
                appendLine("成功: ${result.successCount}  失敗: ${result.failCount}")
                appendLine("耗時: ${result.elapsedMs / 1000}秒")
                appendLine("如有 SSAID，請立即重啟設備後再開啟應用")
            }
            setRunning(false)
            binding.selectDirButton.isEnabled = true
        }
    }

    private fun setRunning(running: Boolean) {
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
