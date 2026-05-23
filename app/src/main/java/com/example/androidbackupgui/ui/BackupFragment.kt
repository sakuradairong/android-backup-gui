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
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.databinding.FragmentBackupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!
    private var apps: List<AppInfo> = emptyList()
    private var selectedApps = mutableSetOf<String>()
    private lateinit var config: BackupConfig

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
    }

    private fun scanApps() {
        binding.backupButton.isEnabled = false
        setRunning(true)
        binding.statusText.text = "正在扫描应用…"

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val thirdParty = AppScanner.scanThirdParty(ctx)
            val system = AppScanner.scanSystem(ctx, config)
            apps = thirdParty + system
            selectedApps.clear()
            selectedApps.addAll(apps.map { it.packageName })

            binding.statusText.text = "共找到 ${apps.size} 个应用，全部已选中"
            binding.backupButton.isEnabled = apps.isNotEmpty()
            setRunning(false)

            setupAppList()
        }
    }

    private fun setupAppList() {
        binding.appList.adapter = PackageListAdapter(apps, selectedApps) { pkg, checked ->
            if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
            binding.statusText.text = "已选择 ${selectedApps.size}/${apps.size} 个应用"
        }
    }

    private fun startBackup() {
        val toBackup = apps.filter { it.packageName in selectedApps }
        if (toBackup.isEmpty()) return

        setRunning(true)
        binding.backupButton.isEnabled = false
        binding.scanButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val outputDir = File(config.outputPath.ifEmpty {
                requireContext().filesDir.absolutePath
            })
            WifiManager.backup(outputDir)
            val result = BackupOperation.backupApps(
                apps = toBackup,
                config = config,
                outputDir = outputDir,
                onProgress = { progress ->
                    val label = toBackup.find { it.packageName == progress.packageName }?.label
                    val name = label?.ifEmpty { progress.packageName } ?: progress.packageName
                    binding.statusText.text =
                        "[${progress.current}/${progress.total}] $name: ${progress.message}"
                }
            )

            // If restic is enabled, snapshot the backup to a restic repository
            var resticSummary: ResticWrapper.BackupSummary? = null
            if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                val binaryPath = ResticBinary.prepare(requireContext())
                if (binaryPath != null) {
                    ResticWrapper.binaryPath = binaryPath
                    ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(requireContext())
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
                        onProgress = { progress ->
                            if (progress.messageType == "status") {
                                binding.statusText.text = "restic: %.0f%% (%d/%d files)".format(
                                    progress.percentDone * 100,
                                    progress.filesDone,
                                    progress.totalFiles
                                )
                            }
                        }
                    )
                    resticResult.fold(
                        onSuccess = { resticSummary = it },
                        onFailure = { e ->
                            binding.statusText.text = "restic 快照失败: ${e.message}"
                        }
                    )
                }
            }

            binding.statusText.text = buildString {
                appendLine("备份完成！")
                appendLine("成功: ${result.successCount}  失败: ${result.failCount}")
                appendLine("耗时: ${result.elapsedMs / 1000}秒")
                appendLine("输出: ${result.outputDir}")
                if (resticSummary != null) {
                    appendLine()
                    appendLine("── Restic 快照 ──")
                    appendLine("ID: ${resticSummary!!.snapshotId.take(8)}…")
                    appendLine("新增: ${resticSummary!!.dataAdded / 1024 / 1024} MB")
                    appendLine("文件: ${resticSummary!!.totalFilesProcessed}")
                }
            }
            setRunning(false)
            binding.scanButton.isEnabled = true
        }
    }

    private fun setRunning(running: Boolean) {
        binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            ResticWrapper.cleanup()
        }
        super.onDestroyView()
        _binding = null
    }
}
