package com.example.androidbackupgui.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.androidbackupgui.R
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
                ResticWrapper.rcloneBinaryPath = ResticBinary.prepareRclone(requireContext()) ?: "rclone"
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
                backendPass = config.resticBackendPass
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
        return try {
            val env = ResticWrapper.buildFullEnv(
                config.resticRepo,
                config.resticPassword,
                config.resticBackend,
                config.resticBackendUrl,
                config.resticBackendUser,
                config.resticBackendPass
            )

            val cmd = ResticWrapper.buildCommandArgs(listOf("dump", snapshotId, filePath))
            val process = ProcessBuilder(cmd)
                .apply { environment().putAll(env) }
                .redirectErrorStream(false)
                .start()

            // Drain stderr in background FIRST to prevent pipe-buffer deadlock
            val stderrDrain = Thread({
                try { process.errorStream.bufferedReader().use { while (it.readLine() != null) {} } } catch (_: Exception) {}
            }, "restic-dump-stderr").apply { isDaemon = true; start() }

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            stderrDrain.join(5000)
            process.waitFor()

            if (process.exitValue() == 0) stdout else null
        } catch (_: Exception) {
            null
        }
    }

    private fun setupAppList() {
        binding.appList.adapter = PackageAdapter(packages, selectedPackages) { pkg, checked ->
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
                        binding.statusText.text =
                            "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
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
                        binding.statusText.text =
                            "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
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

    private class PackageAdapter(
        private val packages: List<String>,
        private val selected: Set<String>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<PackageAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val textView: TextView = view.findViewById(R.id.appName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val card = MaterialCardView(ctx).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
                radius = 12f
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, 0)
                )
            }
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 12, 16, 12)
            }
            val cb = CheckBox(ctx).apply { id = R.id.checkbox }
            val tv = TextView(ctx).apply {
                id = R.id.appName
                setPadding(16, 0, 0, 0)
                textSize = 15f
                setTextColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)
                )
            }
            layout.addView(cb)
            layout.addView(tv)
            card.addView(layout)
            return ViewHolder(card)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pkg = packages[position]
            holder.textView.text = pkg
            holder.checkbox.isChecked = pkg in selected
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                onToggle(pkg, checked)
            }
        }

        override fun getItemCount() = packages.size
    }
}
