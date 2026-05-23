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
import com.example.androidbackupgui.backup.AppInfo
import com.example.androidbackupgui.backup.AppScanner
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.backup.BackupOperation
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
import com.example.androidbackupgui.backup.WifiManager
import com.example.androidbackupgui.databinding.FragmentBackupBinding
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
        binding.statusText.text = "正在掃描應用…"

        viewLifecycleOwner.lifecycleScope.launch {
            val thirdParty = AppScanner.scanThirdParty()
            val system = AppScanner.scanSystem(config)
            apps = thirdParty + system
            selectedApps.clear()
            selectedApps.addAll(apps.map { it.packageName })

            binding.statusText.text = "共找到 ${apps.size} 個應用，全部已選中"
            binding.backupButton.isEnabled = apps.isNotEmpty()
            setRunning(false)

            setupAppList()
        }
    }

    private fun setupAppList() {
        binding.appList.adapter = AppAdapter(apps, selectedApps) { pkg, checked ->
            if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
            binding.statusText.text = "已選擇 ${selectedApps.size}/${apps.size} 個應用"
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
                    binding.statusText.text =
                        "[${progress.current}/${progress.total}] ${progress.packageName}: ${progress.message}"
                }
            )

            // If restic is enabled, snapshot the backup to a restic repository
            var resticSummary: ResticWrapper.BackupSummary? = null
            if (config.resticEnabled == 1 && config.resticRepo.isNotBlank()) {
                val binaryPath = ResticBinary.prepare(requireContext())
                if (binaryPath != null) {
                    ResticWrapper.binaryPath = binaryPath
                    ResticWrapper.rcloneBinaryPath = ResticBinary.prepareRclone(requireContext()) ?: "rclone"
                    binding.statusText.text = "正在寫入 restic 去重倉庫…"
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
                            binding.statusText.text = "restic 快照失敗: ${e.message}"
                        }
                    )
                }
            }

            binding.statusText.text = buildString {
                appendLine("備份完成！")
                appendLine("成功: ${result.successCount}  失敗: ${result.failCount}")
                appendLine("耗時: ${result.elapsedMs / 1000}秒")
                appendLine("輸出: ${result.outputDir}")
                if (resticSummary != null) {
                    appendLine()
                    appendLine("── Restic 快照 ──")
                    appendLine("ID: ${resticSummary!!.snapshotId.take(8)}…")
                    appendLine("新增: ${resticSummary!!.dataAdded / 1024 / 1024} MB")
                    appendLine("檔案: ${resticSummary!!.totalFilesProcessed}")
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
        super.onDestroyView()
        _binding = null
    }

    /** Simple RecyclerView adapter for app list with checkboxes. */
    private class AppAdapter(
        private val apps: List<AppInfo>,
        private val selected: Set<String>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

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
            val app = apps[position]
            holder.textView.text = app.packageName
            holder.checkbox.isChecked = app.packageName in selected
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                onToggle(app.packageName, checked)
            }
        }

        override fun getItemCount() = apps.size
    }
}
