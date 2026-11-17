package com.quickskip.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QuickSkip"

        // 跳过关键字正则
        private val SKIP_REGEX = Regex(
            "^(?:\\d+\\s*[sS秒]?\\s*)?(?:跳过|跳過|Skip|skip|关闭广告|跳过广告)(?:\\s*\\d+\\s*[sS秒]?)?$"
        )

        // 简短包含匹配（避免误触长文本）
        private val KEYWORDS = listOf("跳过", "跳過", "Skip", "skip")

        @Volatile
        var isServiceRunning = false
    }

    private var lastClickTime = 0L
    private var lastPackageName = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "QuickSkip 服务已连接并启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val currentPackage = event.packageName?.toString() ?: ""
        // 忽略自身与系统桌面
        if (currentPackage.isEmpty() || currentPackage == packageName ||
            currentPackage.contains("launcher") || currentPackage.contains("systemui")
        ) {
            return
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val rootNode = rootInActiveWindow ?: return
            try {
                scanAndSkip(rootNode, currentPackage)
            } finally {
                // 回收 rootNode
                try {
                    rootNode.recycle()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun scanAndSkip(rootNode: AccessibilityNodeInfo, currentPackage: String) {
        val now = SystemClock.uptimeMillis()
        // 防抖：同一个包名在 500ms 内不重复触发点击
        if (currentPackage == lastPackageName && now - lastClickTime < 500) {
            return
        }

        val targetNode = findSkipNode(rootNode, 0)
        if (targetNode != null) {
            Log.d(TAG, "发现目标节点: text=${targetNode.text}, desc=${targetNode.contentDescription}")
            val success = performClickAction(targetNode)
            if (success) {
                lastClickTime = now
                lastPackageName = currentPackage
                Log.i(TAG, "成功触发跳过: package=$currentPackage")
            }
            try {
                targetNode.recycle()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * 深度优先遍历寻找包含“跳过”的有效节点
     */
    private fun findSkipNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 20) return null

        // 排除输入框与不可见节点
        if (node.isEditable) return null

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (isSkipMatch(text) || isSkipMatch(desc)) {
            // 确保该节点在屏幕上有实际显示区域
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0 && rect.top >= 0) {
                return node
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSkipNode(child, depth + 1)
            if (result != null) {
                return result
            }
            try {
                child.recycle()
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun isSkipMatch(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 12) return false
        if (SKIP_REGEX.matches(raw)) return true
        for (kw in KEYWORDS) {
            if (raw.contains(kw)) return true
        }
        return false
    }

    /**
     * 优先节点点击，其次寻找可点击父节点，若皆不可点击则使用手势模拟直接点击坐标
     */
    private fun performClickAction(node: AccessibilityNodeInfo): Boolean {
        // 1. 节点自身支持点击
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. 向上查找 3 层父容器是否可点击
        var parent = node.parent
        var count = 0
        while (parent != null && count < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                parent.recycle()
                return true
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
            count++
        }

        // 3. 终极兼容方案：手势模拟直接点击中心坐标
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        return false
    }

    /**
     * 利用 Android 7.0+ dispatchGesture 手势接口直接点击坐标
     */
    private fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.w(TAG, "QuickSkip 服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        Log.i(TAG, "QuickSkip 服务已销毁")
    }
}
