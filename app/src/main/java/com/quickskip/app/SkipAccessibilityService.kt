package com.quickskip.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedHashMap
import kotlin.math.roundToInt

class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private val SKIP_PATTERNS = listOf(
            Regex("""^(?:跳过|跳過|跳过广告|关闭广告|(?i:skip))$"""),
            Regex("""^\d+\s*[sS秒]?\s*[|｜]?\s*(?:跳过|跳過|跳过广告|关闭广告|(?i:skip))\s*[>›]?$"""),
            Regex("""^(?:跳过|跳過|跳过广告|关闭广告|(?i:skip))\s*(?:[|｜]\s*)?(?:\d+\s*[sS秒]?|[(（]\s*\d+\s*[sS秒]?\s*[)）])\s*[>›]?$"""),
            Regex("""^(?:跳过|跳过|跳过广告|关闭广告|(?i:skip))\s*[>›]$""")
        )

        private val DEFAULT_IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter",
            "com.miui.packageinstaller",
            "com.miui.home",
            "com.mipay.wallet",
            "com.miui.tsmclient",
            "com.xiaomi.account",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.baidu.input_mi",
            "com.iflytek.inputmethod.miui",
            "com.google.android.inputmethod.latin",
            "com.tencent.wetype",
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.unionpay"
        )

        private const val FAST_SCAN_INTERVAL = 150L
        private const val ATTEMPT_COOLDOWN = 500L
        private const val ACTION_COOLDOWN = 1000L

        private const val MAX_NODE_DEPTH = 15
        private const val MAX_NODE_COUNT = 300
        private const val MAX_TRACKED_PACKAGES = 32
        private const val MIN_BUTTON_SIZE_DP = 12f

        private const val PREFS_NAME = "quickskip_config"
        private const val KEY_GESTURE_FALLBACK = "enable_gesture_fallback"
        private const val KEY_ACTIVE_DURATION = "active_window_duration_ms"
        private const val KEY_IDLE_INTERVAL = "idle_scan_interval_ms"
        private const val KEY_MIN_SHORT_EDGE_DP = "min_window_short_edge_dp"
        private const val KEY_USER_IGNORED_PKGS = "user_ignored_packages"

        @Volatile
        var isServiceRunning = false

        @Volatile
        var enableGestureFallback = false

        @Volatile
        var activeWindowDurationMs = 8000L

        @Volatile
        var idleScanIntervalMs = 1000L

        @Volatile
        var minWindowShortEdgeDp = 180f

        @Volatile
        var userIgnoredPackages: Set<String> = emptySet()

        fun loadConfig(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enableGestureFallback = prefs.getBoolean(KEY_GESTURE_FALLBACK, false)
            activeWindowDurationMs = prefs.getLong(KEY_ACTIVE_DURATION, 8000L)
            idleScanIntervalMs = prefs.getLong(KEY_IDLE_INTERVAL, 1000L)
            minWindowShortEdgeDp = prefs.getFloat(KEY_MIN_SHORT_EDGE_DP, 180f)
            val savedSet = prefs.getStringSet(KEY_USER_IGNORED_PKGS, emptySet()) ?: emptySet()
            userIgnoredPackages = savedSet.toSet()
        }

        fun saveConfig(
            context: Context,
            gestureFallback: Boolean = enableGestureFallback,
            activeDuration: Long = activeWindowDurationMs,
            idleInterval: Long = idleScanIntervalMs,
            minShortEdgeDp: Float = minWindowShortEdgeDp,
            ignoredPackages: Set<String> = userIgnoredPackages
        ) {
            val immutableCopy = ignoredPackages.toSet()
            enableGestureFallback = gestureFallback
            activeWindowDurationMs = activeDuration
            idleScanIntervalMs = idleInterval
            minWindowShortEdgeDp = minShortEdgeDp
            userIgnoredPackages = immutableCopy

            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GESTURE_FALLBACK, gestureFallback)
                .putLong(KEY_ACTIVE_DURATION, activeDuration)
                .putLong(KEY_IDLE_INTERVAL, idleInterval)
                .putFloat(KEY_MIN_SHORT_EDGE_DP, minShortEdgeDp)
                .putStringSet(KEY_USER_IGNORED_PKGS, immutableCopy)
                .apply()
        }

        fun isServiceEnabledInSettings(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val expectedComponent = ComponentName(context, SkipAccessibilityService::class.java)
            return enabledServices.any { info ->
                info.resolveInfo?.serviceInfo?.let { serviceInfo ->
                    ComponentName(serviceInfo.packageName, serviceInfo.name) == expectedComponent
                } == true
            }
        }
    }

    private var lastPackageName = ""
    private var activeWindowDeadline = 0L

    private val lastScanTimeByPackage =
        LinkedHashMap<String, Long>(MAX_TRACKED_PACKAGES, 0.75f, true)
    private val lastAttemptTime =
        LinkedHashMap<String, Long>(MAX_TRACKED_PACKAGES, 0.75f, true)
    private val lastActionTime =
        LinkedHashMap<String, Long>(MAX_TRACKED_PACKAGES, 0.75f, true)

    override fun onServiceConnected() {
        super.onServiceConnected()
        loadConfig(this)
        isServiceRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (activeWindowDurationMs <= 0L && idleScanIntervalMs <= 0L) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val currentPackage = event.packageName?.toString() ?: return
        if (currentPackage.isEmpty()) return

        val isWindowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val isIgnoredPackage =
            currentPackage == packageName ||
            currentPackage in DEFAULT_IGNORED_PACKAGES ||
            currentPackage in userIgnoredPackages ||
            currentPackage.contains("launcher", ignoreCase = true)

        if (isIgnoredPackage) {
            if (isWindowChanged) {
                lastPackageName = ""
                activeWindowDeadline = 0L
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        val isPackageChanged = currentPackage != lastPackageName

        if (isPackageChanged) {
            val rootNode = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            } ?: return

            try {
                if (rootNode.packageName?.toString() != currentPackage) return

                lastPackageName = currentPackage
                activeWindowDeadline =
                    if (activeWindowDurationMs > 0L) now + activeWindowDurationMs else 0L
                recordTime(lastScanTimeByPackage, currentPackage, now)

                if (!isInCooldown(currentPackage, now)) {
                    scanAndClickWithRoot(rootNode, currentPackage, now)
                }
            } catch (_: Exception) {
            } finally {
                safeRecycle(rootNode)
            }
            return
        }

        if (isWindowChanged || (activeWindowDeadline == 0L && activeWindowDurationMs > 0L)) {
            activeWindowDeadline =
                if (activeWindowDurationMs > 0L) now + activeWindowDurationMs else 0L
        }

        if (isInCooldown(currentPackage, now)) return

        val isInActiveWindow = activeWindowDeadline > 0L && now <= activeWindowDeadline
        val effectiveInterval = when {
            isInActiveWindow -> FAST_SCAN_INTERVAL
            idleScanIntervalMs > 0L -> idleScanIntervalMs
            else -> return
        }

        val previousScan = lastScanTimeByPackage[currentPackage]
        if (previousScan != null && now - previousScan < effectiveInterval) {
            return
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            recordTime(lastScanTimeByPackage, currentPackage, now)

            if (rootNode.packageName?.toString() != currentPackage) return
            scanAndClickWithRoot(rootNode, currentPackage, now)
        } catch (_: Exception) {
        } finally {
            safeRecycle(rootNode)
        }
    }

    private fun isInCooldown(pkg: String, now: Long): Boolean {
        val lastAction = lastActionTime[pkg]
        if (lastAction != null && now - lastAction < ACTION_COOLDOWN) return true
        val lastAttempt = lastAttemptTime[pkg]
        if (lastAttempt != null && now - lastAttempt < ATTEMPT_COOLDOWN) return true
        return false
    }

    private fun scanAndClickWithRoot(
        rootNode: AccessibilityNodeInfo,
        currentPackage: String,
        now: Long
    ) {
        val windowBounds = Rect()
        rootNode.getBoundsInScreen(windowBounds)

        val counter = intArrayOf(0)
        val targetNode = findSkipNode(rootNode, 0, counter) ?: return

        try {
            recordTime(lastAttemptTime, currentPackage, now)

            if (performClickAction(targetNode, windowBounds, currentPackage)) {
                recordTime(lastActionTime, currentPackage, now)
            }
        } finally {
            if (targetNode !== rootNode) {
                safeRecycle(targetNode)
            }
        }
    }

    private fun recordTime(map: LinkedHashMap<String, Long>, pkg: String, time: Long) {
        map[pkg] = time
        while (map.size > MAX_TRACKED_PACKAGES) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    private fun findSkipNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        counter: IntArray
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_NODE_DEPTH || ++counter[0] > MAX_NODE_COUNT) {
            return null
        }

        if (node.isEditable || node.isPassword) return null

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (node.isEnabled && (isSkipMatch(text) || isSkipMatch(desc))) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (node.isVisibleToUser &&
                rect.width() > 0 &&
                rect.height() > 0 &&
                rect.top >= 0
            ) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODE_COUNT) break

            val child = node.getChild(i) ?: continue
            var transferOwnership = false

            try {
                val result = findSkipNode(child, depth + 1, counter)
                if (result != null) {
                    transferOwnership = result === child
                    return result
                }
            } finally {
                if (!transferOwnership) {
                    safeRecycle(child)
                }
            }
        }

        return null
    }

    private fun isSkipMatch(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 16) return false
        return SKIP_PATTERNS.any { it.matches(raw) }
    }

    private fun performClickAction(
        node: AccessibilityNodeInfo,
        windowBounds: Rect,
        expectedPackage: String
    ): Boolean {
        if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
            val clicked = try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
                false
            }
            if (clicked) return true
        }

        var parent = try { node.parent } catch (_: Exception) { null }
        var count = 0

        while (parent != null && count < 3) {
            count++
            val current = parent
            val nextParent: AccessibilityNodeInfo?

            try {
                if (current.isVisibleToUser &&
                    current.isEnabled &&
                    current.isClickable &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    safeRecycle(current)
                    return true
                }
                nextParent = if (count < 3) current.parent else null
            } catch (_: Exception) {
                safeRecycle(current)
                return false
            }

            safeRecycle(current)
            parent = nextParent
        }

        if (!enableGestureFallback) return false

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val winWidth = windowBounds.width()
        val winHeight = windowBounds.height()
        if (winWidth <= 0 || winHeight <= 0) return false

        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val widthDp = winWidth / density
        val heightDp = winHeight / density
        if (minOf(widthDp, heightDp) < minWindowShortEdgeDp) return false

        val tolerantBounds = Rect(windowBounds).apply {
            inset(-4, -4)
        }
        if (!tolerantBounds.contains(rect)) return false
        if (!windowBounds.contains(rect.centerX(), rect.centerY())) return false

        val minButtonPx = (MIN_BUTTON_SIZE_DP * density).roundToInt().coerceAtLeast(1)
        val maxButtonWidthPx = winWidth / 2
        val maxButtonHeightPx = winHeight / 6
        if (rect.width() !in minButtonPx..maxButtonWidthPx ||
            rect.height() !in minButtonPx..maxButtonHeightPx
        ) {
            return false
        }

        val relCenterX = rect.centerX() - windowBounds.left
        val relCenterY = rect.centerY() - windowBounds.top

        val isHorizontalEdge =
            relCenterX < winWidth * 0.25f ||
            relCenterX > winWidth * 0.75f
        val isVerticalEdge =
            relCenterY < winHeight * 0.25f ||
            relCenterY > winHeight * 0.75f

        if (!isHorizontalEdge || !isVerticalEdge) return false

        val activeRoot = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false

        try {
            val activePackage = activeRoot.packageName?.toString()
            if (activePackage != expectedPackage) return false

            val currentWindowBounds = Rect()
            activeRoot.getBoundsInScreen(currentWindowBounds)
            if (currentWindowBounds != windowBounds) return false

            val path = Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            return dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            return false
        } finally {
            safeRecycle(activeRoot)
        }
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        lastScanTimeByPackage.clear()
        lastAttemptTime.clear()
        lastActionTime.clear()
        lastPackageName = ""
        activeWindowDeadline = 0L
        super.onDestroy()
    }
}
