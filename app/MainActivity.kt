package com.example.androidbackupgui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val paramInput = findViewById<EditText>(R.id.paramInput)
        val runButton = findViewById<Button>(R.id.runButton)
        val resultView = findViewById<TextView>(R.id.resultView)

        runButton.setOnClickListener {
            val params = paramInput.text.toString()
            val result = runShellScript(params)
            resultView.text = result
        }
    }

    private fun runShellScript(params: String): String {
        // 假设脚本已复制到 /data/data/com.example.androidbackupgui/files/scripts/tools.sh
        val scriptPath = filesDir.absolutePath + "/scripts/tools.sh"
        val cmd = "sh $scriptPath $params"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            reader.close()
            output.toString()
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        }
    }
}
