package com.example.androidbackupgui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.example.androidbackupgui.backup.LogUtil
import com.example.androidbackupgui.backup.ResticBinary
import com.example.androidbackupgui.backup.ResticWrapper
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
        ResticBinary.prepare(this)?.let { ResticWrapper.binaryPath = it }

        // Initialize file-based logging
        LogUtil.init(filesDir)

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScaffold()
                }
            }
        }
    }
}
