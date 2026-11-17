package com.quickskip.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnOpenAppSettings: Button
    private lateinit var rgPowerMode: RadioGroup
    private lateinit var switchGesture: SwitchMaterial
    private lateinit var etPackageName: EditText
    private lateinit var btnAddPkg: Button
    private lateinit var btnClearPkg: Button
    private lateinit var tvIgnoredList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 载入安全配置
        SkipAccessibilityService.loadConfig(this)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_service_status)
        tvStatusSub = findViewById(R.id.tv_status_sub)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        btnOpenAppSettings = findViewById(R.id.btn_open_app_settings)
        rgPowerMode = findViewById(R.id.rg_power_mode)
        switchGesture = findViewById(R.id.switch_gesture)
        etPackageName = findViewById(R.id.et_package_name)
        btnAddPkg = findViewById(R.id.btn_add_pkg)
        btnClearPkg = findViewById(R.id.btn_clear_pkg)
        tvIgnoredList = findViewById(R.id.tv_ignored_list)

        // 初始化策略选项
        when {
            SkipAccessibilityService.activeWindowDurationMs == 0L &&
                    SkipAccessibilityService.idleScanIntervalMs == 0L -> {
                rgPowerMode.check(R.id.rb_paused)
            }
            SkipAccessibilityService.idleScanIntervalMs == 0L -> {
                rgPowerMode.check(R.id.rb_saver)
            }
            else -> {
                rgPowerMode.check(R.id.rb_balanced)
            }
        }

        // 初始化手势兜底状态
        switchGesture.isChecked = SkipAccessibilityService.enableGestureFallback
        updateIgnoredListText()
    }

    private fun setupListeners() {
        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        btnOpenAppSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        rgPowerMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_balanced -> {
                    SkipAccessibilityService.saveConfig(this, activeDuration = 8000L, idleInterval = 1000L)
                    Toast.makeText(this, "已切换为：均衡模式", Toast.LENGTH_SHORT).show()
                }
                R.id.rb_saver -> {
                    SkipAccessibilityService.saveConfig(this, activeDuration = 8000L, idleInterval = 0L)
                    Toast.makeText(this, "已切换为：极致省电模式", Toast.LENGTH_SHORT).show()
                }
                R.id.rb_paused -> {
                    SkipAccessibilityService.saveConfig(this, activeDuration = 0L, idleInterval = 0L)
                    Toast.makeText(this, "已暂停自动扫描", Toast.LENGTH_SHORT).show()
                }
            }
        }

        switchGesture.setOnClickListener {
            if (switchGesture.isChecked) {
                // 开启前弹出安全风险提示确认
                AlertDialog.Builder(this)
                    .setTitle("开启手势兜底风险提示")
                    .setMessage("针对部分将跳过做成不可点击图片的顽固广告，开启后将尝试直接模拟点击屏幕坐标。\n\n⚠️ 注意：在小窗、悬浮窗或分屏模式下，坐标模拟点击可能穿透到底层界面。是否确定开启？")
                    .setPositiveButton("确定开启") { _, _ ->
                        SkipAccessibilityService.saveConfig(this, gestureFallback = true)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        switchGesture.isChecked = false
                        SkipAccessibilityService.saveConfig(this, gestureFallback = false)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                SkipAccessibilityService.saveConfig(this, gestureFallback = false)
                Toast.makeText(this, "已关闭手势兜底", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddPkg.setOnClickListener {
            val pkg = etPackageName.text?.toString()?.trim() ?: ""
            if (pkg.isNotEmpty()) {
                val currentSet = SkipAccessibilityService.userIgnoredPackages.toMutableSet()
                currentSet.add(pkg)
                SkipAccessibilityService.saveConfig(this, ignoredPackages = currentSet)
                etPackageName.text?.clear()
                updateIgnoredListText()
                Toast.makeText(this, "已添加排除: $pkg", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearPkg.setOnClickListener {
            SkipAccessibilityService.saveConfig(this, ignoredPackages = emptySet())
            updateIgnoredListText()
            Toast.makeText(this, "已清空自定义排除列表", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateIgnoredListText()
    }

    private fun updateServiceStatus() {
        val inSettings = SkipAccessibilityService.isServiceEnabledInSettings(this)
        val running = SkipAccessibilityService.isServiceRunning

        when {
            inSettings && running -> {
                tvStatus.text = "运行正常 (服务已连接)"
                tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                tvStatusSub.text = "无障碍服务已成功连接，开屏跳过功能正常运行中。"
                btnOpenAccessibility.text = "重新查看无障碍设置"
            }
            inSettings && !running -> {
                tvStatus.text = "服务异常 (已开启但进程被系统回收)"
                tvStatus.setTextColor(Color.parseColor("#E65100"))
                tvStatusSub.text = "系统设置中已开启，但后台进程可能已被小米系统回收。建议点击下方按钮，将开关关闭后再重新打开一次。"
                btnOpenAccessibility.text = "前往重启无障碍服务"
            }
            else -> {
                tvStatus.text = "服务未开启"
                tvStatus.setTextColor(Color.parseColor("#D32F2F"))
                tvStatusSub.text = "请点击下方按钮，在系统设置中找到 QuickSkip 并开启无障碍服务。"
                btnOpenAccessibility.text = "前往开启无障碍服务"
            }
        }
    }

    private fun updateIgnoredListText() {
        val set = SkipAccessibilityService.userIgnoredPackages
        if (set.isEmpty()) {
            tvIgnoredList.text = "当前自定义排除列表：无"
        } else {
            tvIgnoredList.text = "当前自定义排除列表：\n${set.joinToString("\n")}"
        }
    }
}
