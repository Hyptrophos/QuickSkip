package com.quickskip.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnAppSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_service_status)
        btnAccessibility = findViewById(R.id.btn_open_accessibility)
        btnAppSettings = findViewById(R.id.btn_open_app_settings)

        btnAccessibility.setOnClickListener {
            // 打开系统无障碍设置页面
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        btnAppSettings = findViewById(R.id.btn_open_app_settings)
        btnAppSettings.setOnClickListener {
            // 打开应用详情页面（用于设置小米自启动、省电策略等）
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this, SkipAccessibilityService::class.java)
        if (isEnabled) {
            tvStatus.text = getString(R.string.service_status_enabled)
            tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            btnAccessibility.text = "服务已开启 (点击可重新查看设置)"
        } else {
            tvStatus.text = getString(R.string.service_status_disabled)
            tvStatus.setTextColor(Color.parseColor("#D32F2F"))
            btnAccessibility.text = getString(R.string.btn_enable_service)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = "${context.packageName}/${service.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
