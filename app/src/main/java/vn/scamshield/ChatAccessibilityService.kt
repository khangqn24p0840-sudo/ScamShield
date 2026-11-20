package vn.scamshield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads visible message text only after explicit in-app consent and Android Accessibility approval.
 * It never takes screenshots, reads editable/password nodes, or scans unselected chat threads.
 */
class ChatAccessibilityService : AccessibilityService() {
    companion object {
        private const val PREF_CONSENT = "screen_chat_accessibility_consent"
        private const val PREF_ENABLED = "screen_chat_monitor_enabled"
        private const val EMPTY_PACKAGE_SENTINEL = "vn.scamshield.no_selected_chat_app"
        private const val TAG = "ScamShieldChatScan"

        @Volatile private var activeService: ChatAccessibilityService? = null

        fun hasConsent(c: Context): Boolean = Store.flag(c, PREF_CONSENT)
        fun isMonitoringEnabled(c: Context): Boolean = Store.flag(c, PREF_ENABLED)

        fun setConsent(c: Context, accepted: Boolean) {
            Store.putFlag(c, PREF_CONSENT, accepted)
        }

        fun setMonitoringEnabled(c: Context, enabled: Boolean) {
            Store.putFlag(c, PREF_ENABLED, enabled)
            activeService?.updatePackageFilter()
        }

        fun isServiceEnabled(c: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                c.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val expected = ComponentName(c, ChatAccessibilityService::class.java).flattenToString()
            return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun refreshPackageFilter(c: Context) {
            activeService?.updatePackageFilter()
        }
    }

    private data class VisibleText(val value: String, val bounds: Rect)
    private data class VisibleMessage(val value: String, val sender: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScamShield-ScreenChat").apply { isDaemon = true }
    }
    private val recentlyCaptured = LinkedHashMap<String, Long>()
    private val recentlyAlerted = LinkedHashMap<String, Long>()
    @Volatile private var allowedPackages: Set<String> = emptySet()
    private var pendingScan: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        updatePackageFilter()
    }

    fun updatePackageFilter() {
        val packages = MonitorStore.targets(this)
            .asSequence()
            .filter { it.channel == "social" && it.appPackage in SocialListener.apps.keys }
            .map { it.appPackage }
            .distinct()
            .toList()
        allowedPackages = packages.toSet()
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 400
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // Empty packageNames can mean "all packages" on some implementations; use a non-app sentinel.
        info.packageNames = if (packages.isEmpty()) arrayOf(EMPTY_PACKAGE_SENTINEL) else packages.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val monitoring = isMonitoringEnabled(this)
        val consent = hasConsent(this)
        if (!monitoring || !consent) {
            Log.d(TAG, "Ignore accessibility event: monitoring=$monitoring consent=$consent")
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in allowedPackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
            else -> return
        }

        pendingScan?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            pendingScan = null
            scanSelectedConversation(pkg)
        }
        pendingScan = task
        mainHandler.postDelayed(task, 350L)
    }

    private fun scanSelectedConversation(packageName: String) {
        val monitoring = isMonitoringEnabled(this)
        val consent = hasConsent(this)
        if (!monitoring || !consent) {
            Log.d(TAG, "Scan stopped: monitoring=$monitoring consent=$consent")
            return
        }
        val root = findAppWindowRoot(packageName)
        if (root == null) {
            Log.w(TAG, "No accessibility root for package=$packageName")
            return
        }
        if (root.packageName?.toString() != packageName) {
            Log.w(TAG, "Accessibility root package mismatch for package=$packageName")
            runCatching { root.recycle() }
            return
        }

        val snapshot = collectVisibleText(root)
        // Some chat apps expose a custom composer that is not reported as an editable node.
        // The selected conversation title below is the actual thread-scope guard.
        Log.d(TAG, "Snapshot package=$packageName nodes=${snapshot.nodes.size} composer=${snapshot.hasComposer}")

        val matchingTargets = MonitorStore.targets(this).filter {
            it.channel == "social" && it.appPackage == packageName
        }
        val headerNodes = snapshot.nodes.filter {
            it.bounds.top >= 0 && it.bounds.top <= (snapshot.screenHeight * 0.28f).toInt()
        }
        val target = matchingTargets.firstOrNull { candidate ->
            val wanted = normalize(candidate.identifier)
            wanted.isNotBlank() && headerNodes.any { header ->
                val visibleTitle = normalize(header.value)
                visibleTitle == wanted || visibleTitle.startsWith("$wanted ")
            }
        }
        if (target == null) {
            Log.w(TAG, "No selected conversation matched; targets=${matchingTargets.size} headerNodes=${headerNodes.size}")
            return
        }
        Log.d(TAG, "Matched selected conversation targetId=${target.id}")

        // Use the same conversation key convention as notification capture when the title is known.
        val conversationKey = packageName + "|" + target.identifier.trim().lowercase(Locale.ROOT)
        // Thu thập cả tin đến (bên trái) và tin đi (bên phải) đang hiển thị.
        // Không đọc ô nhập: collectVisibleText đã loại bỏ mọi node editable/password.
        val visibleMessages = snapshot.nodes
            .asSequence()
            .filter { node ->
                node.value.length in 2..800 &&
                    node.bounds.top >= (snapshot.screenHeight * 0.08f).toInt() &&
                    node.bounds.bottom <= (snapshot.screenHeight * 0.88f).toInt() &&
                    node.bounds.width() < (snapshot.screenWidth * 0.88f).toInt() &&
                    node.bounds.left >= (snapshot.screenWidth * 0.02f).toInt() &&
                    node.bounds.right <= (snapshot.screenWidth * 0.98f).toInt() &&
                    !isCommonUiText(node.value)
            }
            .sortedWith(compareBy<VisibleText> { it.bounds.top }.thenBy { it.bounds.left })
            .map { node ->
                val sender = if (node.bounds.centerX() >= (snapshot.screenWidth * 0.56f).toInt()) {
                    "Bạn"
                } else {
                    "Tin đến · ${target.identifier}"
                }
                VisibleMessage(node.value.trim(), sender)
            }
            .distinctBy { normalize(it.value) }
            .toList()
        if (visibleMessages.isEmpty()) {
            Log.d(TAG, "Matched conversation but no readable message nodes were found")
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val newMessages = visibleMessages.filter { item ->
            markIfNew(
                recentlyCaptured,
                target.id + "|" + normalize(item.value),
                nowElapsed,
                2 * 60 * 1000L
            )
        }
        if (newMessages.isEmpty()) return

        val observedAt = System.currentTimeMillis()
        val title = target.identifier
        runCatching {
            scanner.execute {
                if (!isMonitoringEnabled(this) || !hasConsent(this)) return@execute
                var inserted = 0
                newMessages.forEachIndexed { index, item ->
                    if (MonitorStore.captureScreenSocial(
                            this,
                            target.id,
                            item.value,
                            packageName,
                            observedAt + index,
                            title,
                            conversationKey,
                            item.sender
                        )
                    ) inserted++
                }
                Log.i(TAG, "Screen capture result: visible=${newMessages.size} inserted=$inserted")
                if (inserted == 0) return@execute

                val recent = MonitorStore.recentMessages(this, target.id, conversationKey, 8)
                if (recent.isEmpty()) return@execute
                val transcript = recent.joinToString("\n") { "${it.sender}: ${it.body}" }
                val risk = ScamDetector.analyzeConversation(transcript)
                if (risk.level < 1) return@execute

                val latest = recent.last()
                val alertKey = target.id + "|" + latest.id + "|" + risk.level
                if (!markIfNew(recentlyAlerted, alertKey, SystemClock.elapsedRealtime(), 3 * 60 * 1000L)) {
                    return@execute
                }

                val appName = SocialListener.apps[packageName] ?: "ứng dụng chat"
                val titleText = if (risk.level == 2) {
                    "🚨 ScamShield phát hiện diễn biến chat nguy hiểm"
                } else {
                    "⚠️ ScamShield phát hiện dấu hiệu thao túng"
                }
                val detail = buildString {
                    append("Trong cuộc trò chuyện với ").append(target.identifier).append(" trên ").append(appName).append(". ")
                    append(risk.reasons.joinToString(", "))
                    append(". Hãy dừng lại và kiểm chứng độc lập trước khi làm theo yêu cầu.")
                }.take(900)
                Store.log(this, if (risk.level == 2) 2 else 1, titleText, detail)
                Notifier.show(this, titleText, detail)
                Alerter.fire(this, risk.level)
            }
        }
    }

    private data class ScreenSnapshot(
        val nodes: List<VisibleText>,
        val screenWidth: Int,
        val screenHeight: Int,
        val hasComposer: Boolean
    )

    private fun findAppWindowRoot(packageName: String): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == packageName) return active
        runCatching { active?.recycle() }

        for (window in windows) {
            val candidate = runCatching { window.root }.getOrNull() ?: continue
            if (candidate.packageName?.toString() == packageName) return candidate
            runCatching { candidate.recycle() }
        }
        return null
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): ScreenSnapshot {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val result = mutableListOf<VisibleText>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        var visited = 0
        var hasComposer = false

        while (pending.isNotEmpty() && visited < 1600) {
            val node = pending.removeLast()
            visited++
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val className = node.className?.toString().orEmpty()
                val isInput = node.isEditable ||
                    className.contains("EditText", ignoreCase = true) ||
                    className.contains("AutoCompleteTextView", ignoreCase = true)
                if (isInput && bounds.bottom >= (height * 0.45f).toInt()) {
                    hasComposer = true
                }

                if (node.isVisibleToUser && !node.isPassword && !isInput && !bounds.isEmpty) {
                    // Zalo and other chat apps may expose labels through contentDescription
                    // instead of text. Read both, then let the existing deduplication collapse repeats.
                    val values = listOfNotNull(
                        node.text?.toString()?.trim(),
                        node.contentDescription?.toString()?.trim()
                    ).filter { it.length in 2..800 }.distinct()
                    values.forEach { value ->
                        result += VisibleText(value, Rect(bounds))
                    }
                }

                for (index in 0 until node.childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull()
                    if (child != null) pending.addLast(child)
                }
            } catch (_: Exception) {
                // A live app can replace its view tree while it is being inspected.
            } finally {
                runCatching { node.recycle() }
            }
        }

        while (pending.isNotEmpty()) runCatching { pending.removeLast().recycle() }

        // Collapse duplicate parent/child labels while retaining the narrower text-node bounds.
        val deduplicated = result
            .groupBy { normalize(it.value) + "|" + (it.bounds.centerY() / 10) }
            .values
            .mapNotNull { group -> group.minByOrNull { it.bounds.width() } }

        return ScreenSnapshot(deduplicated, width, height, hasComposer)
    }

    private fun isCommonUiText(value: String): Boolean {
        val text = normalize(value)
        if (text.matches(Regex("^\\d{1,2}[:.]\\d{2}([ ]?(am|pm))?$"))) return true
        val labels = setOf(
            "nhap tin nhan", "soan tin nhan", "gui", "tim kiem",
            "dang hoat dong", "hoat dong", "online", "da xem",
            "da gui", "delivered", "seen", "search", "message"
        )
        return text in labels
    }

    private fun markIfNew(
        cache: LinkedHashMap<String, Long>,
        key: String,
        now: Long,
        ttl: Long
    ): Boolean = synchronized(cache) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttl) iterator.remove()
        }
        if (cache.containsKey(key)) return@synchronized false
        cache[key] = now
        while (cache.size > 512) {
            val first = cache.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            } else break
        }
        true
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingScan?.let(mainHandler::removeCallbacks)
        if (activeService === this) activeService = null
        scanner.shutdownNow()
        super.onDestroy()
    }
}
