package com.example.androidbackupgui.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.PackageName
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.RestoreOperation
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.databinding.FragmentRestoreBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class RestoreFragment : Fragment() {

    private var _binding: FragmentRestoreBinding? = null
    private val binding get() = _binding!!
    private var backupDir: File? = null
    private var packages: List<String> = emptyList()
    private var appInfos: List<AppInfo> = emptyList()
    private var selectedPackages = mutableSetOf<String>()
    private var resticConfig: BackupConfig? = null
    private var selectedSnapshot: ResticWrapper.ResticSnapshot? = null
    private var resticConfigFingerprint: String? = null
    private var selectedUserId: Int = 0
    private var userList: List<Pair<Int, String>> = listOf(0 to "Owner")

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
                ResticWrapper.cacheDir = requireContext().cacheDir.absolutePath
                ResticWrapper.backendDomain = config.resticBackendDomain
                binding.selectResticButton.visibility = View.VISIBLE
            }
        }

        binding.selectDirButton.setOnClickListener { selectBackupDir() }
        binding.selectResticButton.setOnClickListener { selectResticSnapshot() }
        binding.restoreButton.setOnClickListener { startRestore() }

        // Load user profiles
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
        // Re-read config so changes from ConfigFragment take effect immediately
        val configFile = File(requireContext().filesDir, "backup_settings.conf")
        val config = BackupConfig.fromFile(configFile)

        // Detect restic config change — clear stale state if repo/backend changed
        val newFingerprint = "${config.resticRepo}|${config.resticBackend}|${config.resticBackendUrl}"
        if (resticConfigFingerprint != null && resticConfigFingerprint != newFingerprint) {
            selectedSnapshot = null
            packages = emptyList()
            selectedPackages.clear()
            binding.backupDirText.text = ""
            binding.restoreButton.isEnabled = false
            binding.selectResticButton.visibility = View.GONE
        }
        resticConfigFingerprint = newFingerprint

        resticConfig = if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) config else null
        // Skip redundant preparation if binary and backend config are already set
        if (resticConfig != null &&
            ResticWrapper.binaryPath.isNotEmpty() &&
            ResticWrapper.binaryPath != "restic"
        ) {
            binding.selectResticButton.visibility = View.VISIBLE
        } else {
            val binaryPath = ResticBinary.prepare(requireContext())
            if (binaryPath != null && resticConfig != null) {
                ResticWrapper.binaryPath = binaryPath
                ResticWrapper.cacheDir = requireContext().cacheDir.absolutePath
                ResticWrapper.backendDomain = config.resticBackendDomain
                binding.selectResticButton.visibility = View.VISIBLE
            }
        }
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
            binding.statusText.text = "未找到备份目录，请确保 Backup_* 文件夹存在于 ${defaultDir.absolutePath}"
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

        binding.statusText.text = "共 ${packages.size} 个备份应用"
        binding.restoreButton.isEnabled = packages.isNotEmpty()

        appInfos = AppScanner.resolveLabels(requireContext(), packages.map { AppInfo(packageName = PackageName(it)) })
        setupAppList()
    }

    private fun selectResticSnapshot() {
        val config = resticConfig ?: return
        setRunning(true)
        binding.statusText.text = "正在同步远程仓库到本地…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshotsResult = ResticWrapper.listSnapshots(
                    config.resticRepo, config.resticPassword,
                    backend = config.resticBackend,
                    backendUrl = config.resticBackendUrl,
                    backendUser = config.resticBackendUser,
                    backendPass = config.resticBackendPass,
                    backendShare = config.resticBackendShare,
                )
                if (snapshotsResult.isFailure) {
                    updateStatus("读取快照失败: ${snapshotsResult.exceptionOrNull()?.message}")
                    setRunning(false)
                    return@launch
                }

                val snapshots = snapshotsResult.getOrThrow()
                if (snapshots.isEmpty()) {
                    updateStatus("没有可用的 restic 快照")
                    setRunning(false)
                    return@launch
                }

                // 多快照时让用户选择，单个快照自动选
                val chosenSnapshot = if (snapshots.size == 1) {
                    snapshots.first()
                } else {
                    pickSnapshot(snapshots) ?: run {
                        updateStatus("已取消选择")
                        setRunning(false)
                        return@launch
                    }
                }

                // Switch to restic source
                backupDir = null
                selectedSnapshot = chosenSnapshot
                val backupPath = selectedSnapshot!!.paths.firstOrNull() ?: run {
                    updateStatus("快照中找不到备份路径")
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
                    updateStatus("无法从快照读取应用列表")
                    setRunning(false)
                    return@launch
                }

                binding.backupDirText.text = "restic: ${selectedSnapshot!!.time.take(19)} (${snapshots.size} 个快照可用)"
                selectedPackages.clear()

                selectedPackages.addAll(packages)

                // Resolve app labels for display
                appInfos = AppScanner.resolveLabels(requireContext(), packages.map { AppInfo(packageName = PackageName(it)) })

                updateStatus("restic 快照共 ${packages.size} 个应用，点击恢复开始")
                binding.restoreButton.isEnabled = true
                setRunning(false)
                setupAppList()
            } catch (e: Exception) {
                binding.statusText.text = "选择快照失败: ${e.message}"
                setRunning(false)
            }
        }
    }

    /** 多快照时弹出选择对话框。返回用户选择的快照，取消时返回 null。 */
    private suspend fun pickSnapshot(snapshots: List<ResticWrapper.ResticSnapshot>): ResticWrapper.ResticSnapshot? =
        suspendCancellableCoroutine { cont ->
            val names = snapshots.map { "${it.time.take(19)} (${it.id.take(8)})" }
            AlertDialog.Builder(requireContext())
                .setTitle("选择快照")
                .setItems(names.toTypedArray()) { _, i -> cont.resume(snapshots[i]) }
                .setOnCancelListener { cont.resume(null) }
                .show()
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
        binding.appList.adapter = PackageListAdapter(
            appInfos, selectedPackages,
            onToggle = { pkg, checked ->
                if (checked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                binding.statusText.text = "已选择 ${selectedPackages.size}/${packages.size} 个应用"
            }
        )
    }

    private fun startRestore() {
        val toRestore = packages.filter { it in selectedPackages }
        if (toRestore.isEmpty()) return

        setRunning(true)
        binding.restoreButton.isEnabled = false
        binding.selectDirButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = if (selectedSnapshot != null && resticConfig != null) {
                    // Restic restore
                    val snapshot = selectedSnapshot ?: return@launch
                    val config = resticConfig ?: return@launch
                    val backupPath = snapshot.paths.firstOrNull() ?: return@launch
                    val staging = File(requireContext().cacheDir, "restic_restore_${snapshot.shortId}")
                    staging.mkdirs()
                    try {
                        binding.progressBar.isIndeterminate = true

                        binding.statusText.text = "正在从 restic 快照恢复到暂存目录…"
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
                            onProgress = { msg -> withContext(Dispatchers.Main) { binding.statusText.text = msg } }
                        )

                        if (restoreResult.isFailure) {
                            updateStatus("restic 恢复失败: ${restoreResult.exceptionOrNull()?.message}")
                            return@launch
                        }

                        // The restored backup directory: <staging>/<original_absolute_path>
                        val restoredBackupDir = File(staging, backupPath.removePrefix("/"))
                        updateStatus("正在从恢复的备份安装应用…")

                        val r = RestoreOperation.restoreApps(
                            context = requireContext(),
                            backupDir = restoredBackupDir,
                            userId = selectedUserId.toString(),
                            filterPkgs = selectedPackages,
                            onProgress = { progress ->
                                val label = appInfos.find { it.packageName.value == progress.packageName }?.label
                                val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                                binding.statusText.text =
                                    "[${progress.current}/${progress.total}] $name: ${progress.message}"
                            }
                        )
                        // Also restore WiFi if backup exists
                        WifiManager.restore(restoredBackupDir)
                        r
                    } finally {
                        try { staging.deleteRecursively() } catch (_: Exception) {}
                    }
                } else {
                    // Local restore
                    val dir = backupDir ?: return@launch
                    val r = RestoreOperation.restoreApps(
                        context = requireContext(),
                        backupDir = dir,
                        userId = selectedUserId.toString(),
                        filterPkgs = selectedPackages,
                        onProgress = { progress ->
                            val label = appInfos.find { it.packageName.value == progress.packageName }?.label
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
                    appendLine("恢复完成！")
                    appendLine("成功: ${result.successCount}  失败: ${result.failCount}")
                    appendLine("耗时: ${result.elapsedMs / 1000}秒")
                    appendLine("如有 SSAID，请立即重启设备后再开启应用")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.statusText.text = "恢复异常: ${e.message}"
            } finally {
                setRunning(false)
                binding.selectDirButton.isEnabled = true
            }
        }
    }


    private fun setRunning(running: Boolean) {
        _binding?.progressBar?.visibility = if (running) View.VISIBLE else View.GONE
    }

    private suspend fun updateStatus(text: String) {
        _binding?.statusText?.text = text
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
