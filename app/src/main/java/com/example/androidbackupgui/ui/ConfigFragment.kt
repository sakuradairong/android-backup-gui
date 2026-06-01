package com.example.androidbackupgui.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.androidbackupgui.R
import com.example.androidbackupgui.backup.BackupConfig
import com.example.androidbackupgui.databinding.FragmentConfigBinding
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {

    companion object { private const val TAG = "ConfigFragment" }

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private val vm: ConfigViewModel by viewModels()
    private var formLoading = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load config from file into ViewModel state
        vm.load()

        // Populate form fields from initial state (prevents listener chain)
        loadForm()

        // ── Change listeners ─────────────────────────────────────────
        binding.saveConfigButton.setOnClickListener { saveConfig() }
        binding.resticBackendGroup.addOnButtonCheckedListener { _, _, _ ->
            onBackendChanged(); refreshResticStatus()
        }
        binding.resticEnabledSwitch.setOnCheckedChangeListener { _, _ -> refreshResticStatus() }
        binding.resticRepoEdit.doAfterTextChanged {
            if (formLoading) return@doAfterTextChanged
            onFormChanged()
            refreshResticStatus()
        }
        binding.resticBackendUrlEdit.doAfterTextChanged {
            if (formLoading) return@doAfterTextChanged
            onFormChanged()
        }
        binding.resticPasswordEdit.doAfterTextChanged {
            if (formLoading) return@doAfterTextChanged
            refreshResticStatus()
        }
        binding.initResticButton.setOnClickListener { initResticRepo() }
        binding.resticStatsButton.setOnClickListener { showResticStats() }
        binding.resticPruneButton.setOnClickListener { pruneResticSnapshots() }

        // Initial async status check
        refreshResticStatus()

        // Observe ViewModel state for derived UI updates
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state -> applyState(state) }
            }
        }
    }

    // ── Initial form population ──────────────────────────────────────

    /** Populate EditTexts from ViewModel's current config. */
    private fun loadForm() {
        formLoading = true
        val config = vm.uiState.value.config
        binding.backupModeSwitch.isChecked = config.backupMode == 1
        binding.backupUserDataSwitch.isChecked = config.backupUserData == 1
        binding.backupObbSwitch.isChecked = config.backupObbData == 1
        binding.backupWifiSwitch.isChecked = config.backupWifi == 1
        binding.ignoreRunningSwitch.isChecked = config.backgroundAppsIgnore == 1
        binding.outputPathEdit.setText(config.outputPath)
        binding.compressionEdit.setText(config.compressionMethod)

        binding.resticEnabledSwitch.isChecked = config.resticEnabled == 1
        binding.resticRepoEdit.setText(config.resticRepo)
        binding.resticPasswordEdit.setText(config.resticPassword)
        binding.resticBackendUrlEdit.setText(config.resticBackendUrl)
        binding.resticBackendUserEdit.setText(config.resticBackendUser)
        binding.resticBackendPassEdit.setText(config.resticBackendPass)
        binding.resticBackendShareEdit.setText(config.resticBackendShare)
        binding.resticBackendDomainEdit.setText(config.resticBackendDomain)

        binding.resticBackendGroup.check(
            when (config.resticBackend) {
                "webdav" -> R.id.resticBackendWebdav
                "smb" -> R.id.resticBackendSmb
                "rest-server" -> R.id.resticBackendRestServer
                else -> R.id.resticBackendLocal
            }
        )
        formLoading = false
    }

    // ── StateFlow observer ───────────────────────────────────────────

    /** Apply ViewModel state to non-form views (visibility, text, enabled). */
    private fun applyState(state: ConfigUiState) {
        with(state.backendDisplay) {
            binding.resticBackendUrlLayout.visibility = if (isRemote) View.VISIBLE else View.GONE
            binding.resticBackendShareLayout.visibility = if (isSmb) View.VISIBLE else View.GONE
            binding.resticBackendDomainLayout.visibility = if (isSmb) View.VISIBLE else View.GONE
            binding.resticBackendUserLayout.visibility = if (needsAuth) View.VISIBLE else View.GONE
            binding.resticBackendPassLayout.visibility = if (needsAuth) View.VISIBLE else View.GONE
            binding.resticBackendUrlLayout.hint = urlHint
            binding.resticComputedUrlText.text = if (state.config.resticRepo.isNotEmpty())
                "实际仓库: $computedUrl" else ""
        }
        with(state.resticStatus) {
            binding.resticStatusText.text = message
            binding.initResticButton.isEnabled = initButtonEnabled
            binding.initResticButton.visibility = if (initButtonVisible) View.VISIBLE else View.GONE
            binding.resticStatsButton.isEnabled = statsButtonEnabled
            binding.resticStatsButton.visibility = if (statsButtonVisible) View.VISIBLE else View.GONE
            binding.resticPruneButton.isEnabled = pruneButtonEnabled
            binding.resticPruneButton.visibility = if (pruneButtonVisible) View.VISIBLE else View.GONE
        }
    }

    // ── Form building helpers ────────────────────────────────────────

    private fun readBackend(): String = when (binding.resticBackendGroup.checkedButtonId) {
        R.id.resticBackendWebdav -> "webdav"
        R.id.resticBackendSmb -> "smb"
        R.id.resticBackendRestServer -> "rest-server"
        else -> "local"
    }

    private fun readResticForm() = ResticForm(
        repo = binding.resticRepoEdit.text?.toString()?.trim() ?: "",
        password = binding.resticPasswordEdit.text?.toString() ?: "",
        backend = readBackend(),
        backendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: "",
        backendUser = binding.resticBackendUserEdit.text?.toString()?.trim() ?: "",
        backendPass = binding.resticBackendPassEdit.text?.toString() ?: "",
        backendShare = binding.resticBackendShareEdit.text?.toString()?.trim() ?: "",
        backendDomain = binding.resticBackendDomainEdit.text?.toString()?.trim() ?: ""
    )

    // ── User actions ─────────────────────────────────────────────────

    private fun saveConfig() {
        vm.save(BackupConfig().also { config ->
            config.backupMode = if (binding.backupModeSwitch.isChecked) 1 else 0
            config.backupUserData = if (binding.backupUserDataSwitch.isChecked) 1 else 0
            config.backupObbData = if (binding.backupObbSwitch.isChecked) 1 else 0
            config.backgroundAppsIgnore = if (binding.ignoreRunningSwitch.isChecked) 1 else 0
            config.outputPath = binding.outputPathEdit.text?.toString() ?: ""
            config.compressionMethod = binding.compressionEdit.text?.toString()?.ifEmpty { "zstd" } ?: "zstd"
            config.backupWifi = if (binding.backupWifiSwitch.isChecked) 1 else 0

            config.resticEnabled = if (binding.resticEnabledSwitch.isChecked) 1 else 0
            config.resticRepo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
            config.resticPassword = binding.resticPasswordEdit.text?.toString() ?: ""
            config.resticBackend = readBackend()
            config.resticBackendUrl = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
            config.resticBackendUser = binding.resticBackendUserEdit.text?.toString()?.trim() ?: ""
            config.resticBackendPass = binding.resticBackendPassEdit.text?.toString() ?: ""
            config.resticBackendShare = binding.resticBackendShareEdit.text?.toString()?.trim() ?: ""
            config.resticBackendDomain = binding.resticBackendDomainEdit.text?.toString()?.trim() ?: ""
        })
    }

    private fun onFormChanged() {
        val backend = readBackend()
        val repo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
        val url = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
        vm.onFormChanged(backend, repo, url)
    }

    private fun onBackendChanged() {
        val backend = readBackend()
        val repo = binding.resticRepoEdit.text?.toString()?.trim() ?: ""
        val url = binding.resticBackendUrlEdit.text?.toString()?.trim() ?: ""
        vm.onFormChanged(backend, repo, url)
    }

    private fun refreshResticStatus() {
        vm.refreshResticStatus(readResticForm())
    }

    private fun initResticRepo() {
        vm.initResticRepo(readResticForm())
    }

    private fun showResticStats() {
        vm.showResticStats(readResticForm())
    }

    private fun pruneResticSnapshots() {
        vm.pruneResticSnapshots(readResticForm())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // cleanup is handled by ViewModel.onCleared()
    }
}
