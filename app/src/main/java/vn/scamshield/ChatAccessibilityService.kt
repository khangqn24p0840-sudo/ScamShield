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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScamShield-ScreenChat").apply { isDaemon = true }
    }
    private val recentlyCaptured = LinkedHashMap<String, Long>()
    private val recentlyAlerted = LinkedHashMap<String, Long>()
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
        if (!isMonitoringEnabled(this) || !hasConsent(this)) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in SocialListener.apps) return
        if (MonitorStore.targets(this).none { it.channel == "social" && it.appPackage == pkg }) return

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
        if (!isMonitoringEnabled(this) || !hasConsent(this)) return
        val root = findAppWindowRoot(packageName) ?: return
        if (root.packageName?.toString() != packageName) {
            runCatching { root.recycle() }
            return
        }

        val snapshot = collectVisibleText(root)
        if (!snapshot.hasComposer) return

        val matchingTargets = MonitorStore.targets(this).filter {
            it.channel == "social" && it.appPackage == packageName
        }
        val headerText = snapshot.nodes
            .filter { it.bounds.top >= 0 && it.bounds.top <= (snapshot.screenHeight * 0.28f).toInt() }
            .joinToString(" ") { it.value }
            .let(::normalize)
        val target = matchingTargets.firstOrNull {
            val wanted = normalize(it.identifier)
            wanted.isNotBlank() && headerText.contains(wanted)
        } ?: return

        // Use the same conversation key convention as notification capture when the title is known.
        val conversationKey = packageName + "|" + target.identifier.trim().lowercase(Locale.ROOT)
        val incomingBodies = snapshot.nodes
            .asSequence()
            .filter { node ->
                node.value.length in 2..800 &&
                    node.bounds.top >= (snapshot.screenHeight * 0.16f).toInt() &&
                    node.bounds.bottom <= (snapshot.screenHeight * 0.88f).toInt() &&
                    node.bounds.width() < (snapshot.screenWidth * 0.88f).toInt() &&
                    node.bounds.centerX() < (snapshot.screenWidth * 0.50f).toInt() &&
                    !isCommonUiText(node.value)
            }
            .sortedWith(compareBy<VisibleText> { it.bounds.top }.thenBy { it.bounds.left })
            .map { it.value.trim() }
            .distinctBy(::normalize)
            .toList()
        if (incomingBodies.isEmpty()) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val newBodies = incomingBodies.filter { body ->
            markIfNew(
                recentlyCaptured,
                target.id + "|" + normalize(body),
                nowElapsed,
                2 * 60 * 1000L
            )
        }
        if (newBodies.isEmpty()) return

        val observedAt = System.currentTimeMillis()
        val title = target.identifier
        runCatching {
            scanner.execute {
                if (!isMonitoringEnabled(this) || !hasConsent(this)) return@execute
                var inserted = 0
                newBodies.forEach { body ->
                    if (MonitorStore.captureScreenSocial(
                            this,
                            target.id,
                            body,
                            packageName,
                            observedAt,
                            title,
                            conversationKey
                        )
                    ) inserted++
                }
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
                if (isInput && bounds.bottom >= (height * 0.68f).toInt()) {
                    hasComposer = true
                }

                if (node.isVisibleToUser && !node.isPassword && !isInput && !bounds.isEmpty) {
                    val value = node.text?.toString()?.trim().orEmpty()
                    if (value.length in 2..800) result += VisibleText(value, Rect(bounds))
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
