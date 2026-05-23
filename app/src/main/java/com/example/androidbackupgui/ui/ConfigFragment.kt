package com.example.androidbackupgui.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.androidbackupgui.R
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.databinding.FragmentConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private lateinit var config: BackupConfig
    private lateinit var configFile: File

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configFile = File(requireContext().filesDir, "backup_settings.conf")
        config = BackupConfig.fromFile(configFile)
        loadConfig()

        binding.saveConfigButton.setOnClickListener { saveConfig() }
        binding.resticBackendGroup.addOnButtonCheckedListener { _, _, _ -> updateBackendFieldVisibility() }
        binding.initResticButton.setOnClickListener { initResticRepo() }
        binding.resticStatsButton.setOnClickListener { showResticStats() }
        binding.resticPruneButton.setOnClickListener { pruneResticSnapshots() }
    }

    private fun loadConfig() {
        binding.backupModeSwitch.isChecked = config.backupMode == 1
        binding.backupUserDataSwitch.isChecked = config.backupUserData == 1
        binding.backupObbSwitch.isChecked = config.backupObbData == 1
        binding.backupWifiSwitch.isChecked = config.backupWifi == 1
        binding.ignoreRunningSwitch.isChecked = config.backgroundAppsIgnore == 1
        binding.outputPathEdit.setText(config.outputPath)
        binding.compressionEdit.setText(config.compressionMethod)

        // Restic settings
        binding.resticEnabledSwitch.isChecked = config.resticEnabled == 1
        binding.resticRepoEdit.setText(config.resticRepo)
        binding.resticPasswordEdit.setText(config.resticPassword)
        binding.resticBackendUrlEdit.setText(config.resticBackendUrl)
        binding.resticBackendUserEdit.setText(config.resticBackendUser)
        binding.resticBackendPassEdit.setText(config.resticBackendPass)

        // Restore backend selector
        binding.resticBackendGroup.check(
            when (config.resticBackend) {
                "webdav" -> R.id.resticBackendWebdav
                "smb" -> R.id.resticBackendSmb
                else -> R.id.resticBackendLocal
            }
        )
        updateBackendFieldVisibility()
        updateComputedUrl()
        refreshResticStatus()
    }

    private fun saveConfig() {
        config.backupMode = if (binding.backupModeSwitch.isChecked) 1 else 0
        config.backupUserData = if (binding.backupUserDataSwitch.isChecked) 1 else 0
        config.backupObbData = if (binding.backupObbSwitch.isChecked) 1 else 0
        config.backgroundAppsIgnore = if (binding.ignoreRunningSwitch.isChecked) 1 else 0
        config.outputPath = binding.outputPathEdit.text?.toString() ?: ""
        config.compressionMethod = binding.compressionEdit.text?.toString()?.ifEmpty { "zstd" } ?: "zstd"
        config.backupWifi = if (binding.backupWifiSwitch.isChecked) 1 else 0

        // Restic settings
        config.resticEnabled = if (binding.resticEnabledSwitch.isChecked) 1 else 0
        config.resticRepo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
        config.resticPassword = binding.resticPasswordEdit.text?.toString() ?: ""
        config.resticBackend = when (binding.resticBackendGroup.checkedButtonId) {
            R.id.resticBackendWebdav -> "webdav"
            R.id.resticBackendSmb -> "smb"
            else -> "local"
        }
        config.resticBackendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
        config.resticBackendUser = binding.resticBackendUserEdit.text?.toString()?.trim() ?: ""
        config.resticBackendPass = binding.resticBackendPassEdit.text?.toString() ?: ""

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BackupConfig.toFile(config, configFile)
            }
            binding.configStatusText.text = "配置已保存到 ${configFile.absolutePath}"
        }
    }

    private fun initResticRepo() {
        val binaryPath = ResticBinary.prepare(requireContext())
        if (binaryPath == null) {
            binding.resticStatusText.text = "restic 二進制未就緒，請確保已安裝 restic 於 Termux 或 APK 內置版本可用"
            return
        }
        ResticWrapper.binaryPath = binaryPath

        val repo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
        val password = binding.resticPasswordEdit.text?.toString() ?: ""
        if (repo.isEmpty() || password.isEmpty()) {
            binding.resticStatusText.text = "請填寫倉庫路徑和密碼"
            return
        }

        binding.initResticButton.isEnabled = false
        binding.resticStatusText.text = "正在初始化 restic 倉庫…"

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ResticWrapper.init(repo, password,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass)
            result.fold(
                onSuccess = {
                    binding.resticStatusText.text = "倉庫初始化成功: $repo"
                    refreshResticStatus()
                },
                onFailure = { e -> binding.resticStatusText.text = "初始化失敗: ${e.message}" }
            )
            binding.initResticButton.isEnabled = true
        }
    }

    /** Refresh the restic management buttons visibility based on repo state. */
    private fun refreshResticStatus() {
        if (config.resticEnabled != 1 || config.resticRepo.isBlank()) {
            binding.initResticButton.visibility = View.GONE
            binding.resticStatsButton.visibility = View.GONE
            binding.resticPruneButton.visibility = View.GONE
            return
        }

        val binaryPath = ResticBinary.prepare(requireContext())
        if (binaryPath == null) {
            binding.initResticButton.visibility = View.VISIBLE
            binding.resticStatsButton.visibility = View.GONE
            binding.resticPruneButton.visibility = View.GONE
            binding.resticStatusText.text = "restic 二進制未就緒"
            return
        }

        ResticWrapper.binaryPath = binaryPath

        // Check if repo is initialized by listing snapshots
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshotsResult = ResticWrapper.listSnapshots(config.resticRepo, config.resticPassword,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass)
            if (snapshotsResult.isSuccess) {
                val snapshots = snapshotsResult.getOrDefault(emptyList())
                binding.initResticButton.visibility = View.GONE
                binding.resticStatsButton.visibility = View.VISIBLE
                binding.resticPruneButton.visibility = View.VISIBLE
                binding.resticStatusText.text = "倉庫就緒，${snapshots.size} 個快照"
            } else {
                binding.initResticButton.visibility = View.VISIBLE
                binding.resticStatsButton.visibility = View.GONE
                binding.resticPruneButton.visibility = View.GONE
                binding.resticStatusText.text = "倉庫未初始化或認證失敗"
            }
        }
    }

    private fun showResticStats() {
        binding.resticStatsButton.isEnabled = false
        binding.resticStatusText.text = "正在讀取統計…"
        viewLifecycleOwner.lifecycleScope.launch {
            val statsResult = ResticWrapper.stats(config.resticRepo, config.resticPassword,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass)
            val snapshotsResult = ResticWrapper.listSnapshots(config.resticRepo, config.resticPassword,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass)
            binding.resticStatsButton.isEnabled = true

            val snapshotCount = snapshotsResult.getOrDefault(emptyList()).size
            binding.resticStatusText.text = buildString {
                appendLine("快照數: $snapshotCount")
                if (statsResult.isSuccess) {
                    appendLine(statsResult.getOrDefault(""))
                } else {
                    appendLine("統計讀取失敗: ${statsResult.exceptionOrNull()?.message}")
                }
            }
        }
    }

    private fun pruneResticSnapshots() {
        binding.resticPruneButton.isEnabled = false
        binding.resticStatusText.text = "正在清理舊快照 (保留 7 天 / 4 週 / 3 月)…"
        viewLifecycleOwner.lifecycleScope.launch {
            val forgetResult = ResticWrapper.forget(
                config.resticRepo, config.resticPassword,
                keepDaily = 7, keepWeekly = 4, keepMonthly = 3,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass
            )
            if (forgetResult.isFailure) {
                binding.resticStatusText.text = "forget 失敗: ${forgetResult.exceptionOrNull()?.message}"
                binding.resticPruneButton.isEnabled = true
                return@launch
            }

            binding.resticStatusText.text = "正在回收空間…"
            val pruneResult = ResticWrapper.prune(config.resticRepo, config.resticPassword,
                backend = config.resticBackend,
                backendUrl = config.resticBackendUrl,
                backendUser = config.resticBackendUser,
                backendPass = config.resticBackendPass)
            binding.resticPruneButton.isEnabled = true

            if (pruneResult.isSuccess) {
                binding.resticStatusText.text = "清理完成！\n${pruneResult.getOrDefault("")}"
                refreshResticStatus()
            } else {
                binding.resticStatusText.text = "prune 失敗: ${pruneResult.exceptionOrNull()?.message}"
            }
        }
    }

    /** Show/hide backend URL/user/pass fields based on selected backend. */
    private fun updateBackendFieldVisibility() {
        val backend = when (binding.resticBackendGroup.checkedButtonId) {
            R.id.resticBackendWebdav -> "webdav"
            R.id.resticBackendSmb -> "smb"
            else -> "local"
        }
        val isRemote = backend != "local"
        binding.resticBackendUrlLayout.visibility = if (isRemote) View.VISIBLE else View.GONE
        binding.resticBackendUserLayout.visibility = if (isRemote) View.VISIBLE else View.GONE
        binding.resticBackendPassLayout.visibility = if (isRemote) View.VISIBLE else View.GONE

        // Update URL field hint
        binding.resticBackendUrlLayout.hint = when (backend) {
            "webdav" -> "WebDAV 地址 (https://host:port/path)"
            "smb" -> "SMB 主機位址 (host 或 host:port)"
            else -> ""
        }
        updateComputedUrl()
    }

    /** Show the computed restic repo URL. */
    private fun updateComputedUrl() {
        val backend = when (binding.resticBackendGroup.checkedButtonId) {
            R.id.resticBackendWebdav -> "webdav"
            R.id.resticBackendSmb -> "smb"
            else -> "local"
        }
        val repo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
        val url = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
        val repoUrl = ResticWrapper.buildRepoUrl(backend, repo, url)
        binding.resticComputedUrlText.text = if (repo.isNotEmpty())
            "實際倉庫: $repoUrl" else ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
