package com.example.androidbackupgui
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.androidbackupgui.backup.core.LogUtil
import com.example.androidbackupgui.backup.security.MissingAlgoProvider
import com.example.androidbackupgui.backup.security.PasswordManager
import com.example.androidbackupgui.backup.security.ResticBinary
import com.example.androidbackupgui.backup.restic.defaultResticWrapper
import com.example.androidbackupgui.root.RootShell
import com.example.androidbackupgui.ui.AppScaffold
import com.example.androidbackupgui.ui.theme.AppTheme
import com.google.android.material.color.DynamicColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Apply Dynamic Colors (Material You) if available
        DynamicColors.applyToActivitiesIfAvailable(application)
        RootShell.configure()

        // Initialize restic binary path
        ResticBinary.prepare(this)?.let { defaultResticWrapper.binaryPath = it }

        // Initialize file-based logging and secure credential storage
        LogUtil.init(filesDir)
        PasswordManager.init(this)
        // 启动时初始化 SMB 加密库（MD4/AESCMAC），避免首次 SMB 操作时延迟失败
        MissingAlgoProvider.register()

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppScaffold()
                }
            }
        }
    }
}
