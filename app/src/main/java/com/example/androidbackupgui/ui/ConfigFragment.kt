package com.example.androidbackupgui.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
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

    companion object { private const val TAG = "ConfigFragment" }

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
        binding.resticEnabledSwitch.setOnCheckedChangeListener { _, _ -> refreshResticStatus() }
        binding.resticRepoEdit.doAfterTextChanged { refreshResticStatus(); updateComputedUrl() }
        binding.resticBackendUrlEdit.doAfterTextChanged { updateComputedUrl() }
        binding.resticPasswordEdit.doAfterTextChanged { refreshResticStatus() }
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
        binding.resticBackendShareEdit.setText(config.resticBackendShare)

        // Restore backend selector
        binding.resticBackendGroup.check(
            when (config.resticBackend) {
                "webdav" -> R.id.resticBackendWebdav
                "smb" -> R.id.resticBackendSmb
                "rest-server" -> R.id.resticBackendRestServer
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
            R.id.resticBackendRestServer -> "rest-server"
            else -> "local"
        }
        config.resticBackendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
        config.resticBackendUser = binding.resticBackendUserEdit.text?.toString()?.trim() ?: ""
        config.resticBackendPass = binding.resticBackendPassEdit.text?.toString() ?: ""
        config.resticBackendShare = binding.resticBackendShareEdit.text?.toString()?.trim() ?: ""

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BackupConfig.toFile(config, configFile)
            }
            binding.configStatusText.text = "配置已保存到 ${configFile.absolutePath}"
        }
    }

    /** Read restic credentials from current UI state (always fresh, avoids stale config). */
    private data class ResticUiState(
        val repo: String, val password: String,
        val backend: String, val backendUrl: String,
        val backendUser: String, val backendPass: String,
        val backendShare: String
    )

    private fun readResticUiState() = ResticUiState(
        repo = binding.resticRepoEdit.text?.toString()?.trim() ?: "",
        password = binding.resticPasswordEdit.text?.toString() ?: "",
        backend = when (binding.resticBackendGroup.checkedButtonId) {
            R.id.resticBackendWebdav -> "webdav"
            R.id.resticBackendSmb -> "smb"
            R.id.resticBackendRestServer -> "rest-server"
            else -> "local"
        },
        backendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: "",
        backendUser = binding.resticBackendUserEdit.text?.toString()?.trim() ?: "",
        backendPass = binding.resticBackendPassEdit.text?.toString() ?: "",
        backendShare = binding.resticBackendShareEdit.text?.toString()?.trim() ?: ""
    )

    private fun initResticRepo() {
        Log.i(TAG, "initResticRepo called")
        val binaryPath = ResticBinary.prepare(requireContext())
        if (binaryPath == null) {
            Log.e(TAG, "initResticRepo: binaryPath is null, showing error to user")
            binding.resticStatusText.text = "restic 二進制未就緒，請確保已安裝 restic 於 Termux 或 APK 內置版本可用"
            return
        }
        Log.i(TAG, "initResticRepo: binaryPath=$binaryPath")
        ResticWrapper.binaryPath = binaryPath
        ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(requireContext())
        val ui = readResticUiState()
        Log.i(TAG, "initResticRepo: repo=${ui.repo} backend=${ui.backend} backendUrl=${ui.backendUrl} share=${ui.backendShare} user=${ui.backendUser}")
        if (ui.repo.isEmpty() || ui.password.isEmpty()) {
            binding.resticStatusText.text = "請填寫倉庫路徑和密碼"
            return
        }

        binding.initResticButton.isEnabled = false
        binding.resticStatusText.text = "正在初始化 restic 倉庫…"

        viewLifecycleOwner.lifecycleScope.launch {
            val result = ResticWrapper.init(ui.repo, ui.password,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "initResticRepo: SUCCESS")
                    binding.resticStatusText.text = "倉庫初始化成功: ${ui.repo}"
                    refreshResticStatus()
                },
                onFailure = { e ->
                    Log.e(TAG, "initResticRepo: FAILED ${e.message}", e)
                    binding.resticStatusText.text = "初始化失敗: ${e.message}"
                }
            )
            binding.initResticButton.isEnabled = true
        }
    }

    /** Refresh the restic management buttons visibility based on repo state. */
    private fun refreshResticStatus() {
        // If restic is disabled entirely, hide everything
        if (!binding.resticEnabledSwitch.isChecked) {
            binding.initResticButton.visibility = View.GONE
            binding.resticStatsButton.visibility = View.GONE
            binding.resticPruneButton.visibility = View.GONE
            binding.resticStatusText.text = ""
            return
        }

        val ui = readResticUiState()

        // Repo path not filled yet — show init button so user can get started
        if (ui.repo.isBlank()) {
            binding.initResticButton.visibility = View.VISIBLE
            binding.resticStatsButton.visibility = View.GONE
            binding.resticPruneButton.visibility = View.GONE
            binding.resticStatusText.text = "請填寫倉庫路徑和密碼後初始化"
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
        ResticWrapper.tempRepoDir = ResticBinary.getTempRepoDir(requireContext())
        // Check if repo is initialized by listing snapshots
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshotsResult = ResticWrapper.listSnapshots(ui.repo, ui.password,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare)
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
        val ui = readResticUiState()
        viewLifecycleOwner.lifecycleScope.launch {
            val statsResult = ResticWrapper.stats(ui.repo, ui.password,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare)
            val snapshotsResult = ResticWrapper.listSnapshots(ui.repo, ui.password,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare)
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
        val ui = readResticUiState()
        viewLifecycleOwner.lifecycleScope.launch {
            val forgetResult = ResticWrapper.forget(
                ui.repo, ui.password,
                keepDaily = 7, keepWeekly = 4, keepMonthly = 3,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare
            )
            if (forgetResult.isFailure) {
                binding.resticStatusText.text = "forget 失敗: ${forgetResult.exceptionOrNull()?.message}"
                binding.resticPruneButton.isEnabled = true
                return@launch
            }

            binding.resticStatusText.text = "正在回收空間…"
            val pruneResult = ResticWrapper.prune(ui.repo, ui.password,
                backend = ui.backend,
                backendUrl = ui.backendUrl,
                backendUser = ui.backendUser,
                backendPass = ui.backendPass,
                backendShare = ui.backendShare)
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
            R.id.resticBackendRestServer -> "rest-server"
            else -> "local"
        }
        val isRemote = backend != "local"
        val needsAuth = backend == "webdav" || backend == "smb"
        val isSmb = backend == "smb"
        binding.resticBackendUrlLayout.visibility = if (isRemote) View.VISIBLE else View.GONE
        binding.resticBackendShareLayout.visibility = if (isSmb) View.VISIBLE else View.GONE
        binding.resticBackendUserLayout.visibility = if (needsAuth) View.VISIBLE else View.GONE
        binding.resticBackendPassLayout.visibility = if (needsAuth) View.VISIBLE else View.GONE

        // Update URL field hint
        binding.resticBackendUrlLayout.hint = when (backend) {
            "webdav" -> "WebDAV 地址 (https://host:port/path)"
            "smb" -> "SMB 主機位址 (host 或 host:port)"
            "rest-server" -> "rest-server 地址 (http://host:port)"
            else -> ""
        }
        updateComputedUrl()
    }

    /** Show the computed restic repo URL. */
    private fun updateComputedUrl() {
        val backend = when (binding.resticBackendGroup.checkedButtonId) {
            R.id.resticBackendWebdav -> "webdav"
            R.id.resticBackendSmb -> "smb"
            R.id.resticBackendRestServer -> "rest-server"
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
