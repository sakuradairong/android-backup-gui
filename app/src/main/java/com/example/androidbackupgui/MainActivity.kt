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
import com.example.androidbackupgui.backup.restic.DefaultResticSessionFactory
import com.example.androidbackupgui.backup.restic.ResticSessionFactory
import com.example.androidbackupgui.backup.security.MissingAlgoProvider
import com.example.androidbackupgui.backup.security.PasswordManager
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

        // 初始化 restic 会话：通过工厂统一处理 binaryPath / cacheDir / backendDomain。
        // backendDomain 在此使用空字符串占位，后续备份/恢复操作会通过
        // ResticSessionFactory.prepare(context, config.resticBackendDomain) 覆盖。
        val resticSessionFactory: ResticSessionFactory = DefaultResticSessionFactory()
        resticSessionFactory.prepare(this, backendDomain = "")

        // Initialize file-based logging and secure credential storage
        LogUtil.init(filesDir)
        PasswordManager.init(this)
        PasswordManager.lastInitError()?.let { err ->
            // 不抛异常让 app 崩溃：fail-soft。密码会回退到 BackupConfig 字段。
            // 仍记 Log.e 让 v1.17 阶段 1-3 引入的故障可观测。
            LogUtil.e(
                "MainActivity",
                "PasswordManager init failed (continuing without encrypted storage): ${err.javaClass.simpleName}: ${err.message}",
            )
            android.util.Log.e("MainActivity", "PasswordManager init failed", err)
        }
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
