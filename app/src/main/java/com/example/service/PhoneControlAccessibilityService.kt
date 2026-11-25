package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class PhoneControlAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneControlAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    private val cachedNodes = mutableListOf<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AssistantRuntimeState.update { it.copy(isAccessibilityEnabled = true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Keeps accessibility window state fresh when queried on demand.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        clearNodeCache()
        instance = null
        AssistantRuntimeState.update { it.copy(isAccessibilityEnabled = false) }
    }

    private fun clearNodeCache() {
        cachedNodes.clear()
    }

    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                w.root?.let { list.add(it) }
            }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { active ->
            if (list.none { it == active }) {
                list.add(0, active)
            }
        }
        return list
    }

    fun captureScreenSummary(): String {
        val root = rootInActiveWindow ?: return "Hozirgi ekranni o'qib bo'lmadi."
        clearNodeCache()

        val sb = StringBuilder()
        val pkg = root.packageName?.toString() ?: "noma'lum"
        sb.appendLine("Faol ilova paketi: $pkg")
        sb.appendLine("Ekrandagi elementlar:")

        traverseNode(root, depth = 0, maxNodes = 85, sb = sb)
        if (cachedNodes.isEmpty()) {
            sb.appendLine("(Matnli yoki bosiladigan element topilmadi)")
        }
        return sb.toString()
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxNodes: Int,
        sb: StringBuilder
    ) {
        if (cachedNodes.size >= maxNodes || depth > 18) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else ""
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable

        val isMeaningful = text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || isEditable || isClickable

        if (isMeaningful && node.isVisibleToUser) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val index = cachedNodes.size
                cachedNodes.add(node)
                val flags = buildList {
                    if (isClickable) add("bosiladi")
                    if (isEditable) add("yoziladi")
                    if (isScrollable) add("aylanadi")
                    if (node.isChecked) add("belgilangan")
                }.joinToString(",")
                val labelPart = buildString {
                    if (text.isNotEmpty()) append("matn=\"${text.take(80)}\" ")
                    if (desc.isNotEmpty() && desc != text) append("tavsif=\"${desc.take(60)}\" ")
                    if (hint.isNotEmpty() && hint != text) append("yordam=\"${hint.take(40)}\" ")
                    if (viewId.isNotEmpty()) append("id=$viewId ")
                }.trim()
                sb.appendLine("[$index] $labelPart [$flags] (${rect.centerX()},${rect.centerY()})")
            }
        }

        for (i in 0 until node.childCount) {
            if (cachedNodes.size >= maxNodes) break
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1, maxNodes, sb)
        }
    }

    suspend fun clickElement(target: String): String {
        val trimmed = target.trim()
        val cleanIdx = trimmed.removePrefix("#").removePrefix("[").removeSuffix("]").toIntOrNull()
        if (cleanIdx != null && cleanIdx in cachedNodes.indices) {
            val node = cachedNodes[cleanIdx]
            if (clickNodeOrParent(node)) {
                return "[$cleanIdx] element bosildi."
            }
        }

        val roots = getAllWindowRoots()
        for (root in roots) {
            val matched = findBestMatchingNode(root, trimmed)
            if (matched != null && clickNodeOrParent(matched)) {
                return "'$trimmed' elementi topildi va bosildi."
            }
        }

        val coords = trimmed.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (coords.size == 2) {
            val ok = tapCoordinates(coords[0], coords[1])
            return if (ok) "Koordinata bosildi." else "Bosib bo'lmadi."
        }

        return "'$trimmed' nomli element ekranda topilmadi."
    }

    private suspend fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var steps = 0
        while (current != null && steps < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            steps++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    suspend fun typeText(text: String, targetField: String? = null): String {
        val root = rootInActiveWindow ?: return "Ekran mavjud emas."

        var targetNode: AccessibilityNodeInfo? = null
        if (!targetField.isNullOrBlank()) {
            val idx = targetField.trim().removePrefix("#").removePrefix("[").removeSuffix("]").toIntOrNull()
            if (idx != null && idx in cachedNodes.indices && cachedNodes[idx].isEditable) {
                targetNode = cachedNodes[idx]
            } else {
                targetNode = findBestMatchingNode(root, targetField, requireEditable = true)
                    ?: findBestMatchingNode(root, targetField)
            }
        }

        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
                ?: findFirstEditable(root)
        }

        if (targetNode == null) {
            return "Ekranda matn kiritish maydoni topilmadi."
        }

        clickNodeOrParent(targetNode)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "'$text' matni kiritildi." else "Matnni kiritib bo'lmadi."
    }

    suspend fun scrollScreen(direction: String): String {
        val root = rootInActiveWindow ?: return "Ekran mavjud emas."
        val dir = direction.lowercase().trim()

        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val (startX, startY, endX, endY) = when (dir) {
            "up", "yuqoriga", "tepa" -> listOf(w * 0.5f, h * 0.35f, w * 0.5f, h * 0.78f)
            "left", "chapga" -> listOf(w * 0.2f, h * 0.5f, w * 0.85f, h * 0.5f)
            "right", "o'ngga", "ongga" -> listOf(w * 0.85f, h * 0.5f, w * 0.2f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.28f)
        }

        val scrollable = findFirstScrollable(root)
        if (scrollable != null && (dir == "down" || dir == "pastga" || dir == "up" || dir == "yuqoriga")) {
            val action = if (dir == "up" || dir == "yuqoriga") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) {
                return "Ekran $dir tomonga aylantirildi."
            }
        }

        val ok = swipeCoordinates(startX, startY, endX, endY)
        return if (ok) "Ekran $dir tomonga surildi." else "Ekranni surib bo'lmadi."
    }

    suspend fun swipeUpLockScreen(): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return swipeCoordinates(w * 0.5f, h * 0.85f, w * 0.5f, h * 0.22f)
    }

    suspend fun unlockDeviceWithPin(savedPin: String): String {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) {
            return "Telefon allaqachon qulfdan ochiq."
        }

        // 1. Swipe up to dismiss lock clock and bring up PIN/Password keypad
        swipeUpLockScreen()
        delay(600L)

        if (!km.isKeyguardLocked) {
            return "Telefon qulfdan ochildi."
        }

        if (savedPin.isBlank()) {
            return "Telefon qulflangan. Avtomatik ochish uchun Sozlamalar bo'limida PIN kodingizni saqlang."
        }

        // 2. Check if there is an editable password/PIN field
        val roots = getAllWindowRoots()
        for (root in roots) {
            val edit = findFirstEditable(root)
            if (edit != null) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, savedPin)
                }
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(250L)
                // Try clicking enter/done button if present
                findKeypadActionNode(root, listOf("key_enter", "enter", "ok", "done", "confirm", "next"))?.let {
                    clickNodeOrParent(it)
                }
                delay(500L)
                return if (!km.isKeyguardLocked) {
                    "Telefon qulfdan ochildi."
                } else {
                    "PIN kod kiritildi, telefon ochilmoqda."
                }
            }
        }

        // 3. Otherwise click numeric keypad buttons digit by digit across all interactive windows
        var clickedCount = 0
        for (ch in savedPin) {
            val digit = ch.toString()
            val refreshedRoots = getAllWindowRoots()
            var digitNode: AccessibilityNodeInfo? = null
            for (r in refreshedRoots) {
                digitNode = findDigitKeyNode(r, digit)
                if (digitNode != null) break
            }
            if (digitNode != null) {
                if (clickNodeOrParent(digitNode)) {
                    clickedCount++
                }
                delay(160L)
            }
        }

        if (clickedCount == 0) {
            return "Qulf ekranidagi raqamli tugmalar topilmadi. Iltimos telefon qulfini tekshiring."
        }

        delay(220L)
        // Tap Enter/Confirm key if device doesn't auto-unlock on last digit
        if (km.isKeyguardLocked) {
            val afterRoots = getAllWindowRoots()
            for (r in afterRoots) {
                val enterNode = findKeypadActionNode(
                    r,
                    listOf("key_enter", "enter", "ok", "confirm", "done", "tasdiq")
                )
                if (enterNode != null) {
                    clickNodeOrParent(enterNode)
                    delay(400L)
                    break
                }
            }
        }

        return if (!km.isKeyguardLocked) {
            "Telefon muvaffaqiyatli qulfdan ochildi."
        } else {
            "PIN kod terildi."
        }
    }

    private fun findDigitKeyNode(root: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val idCandidates = setOf("key$digit", "digit_$digit", "btn_$digit", "num_$digit", "pin_$digit")

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.lowercase()?.substringAfterLast("/") ?: ""

                if (text == digit || desc == digit || viewId in idCandidates) {
                    return node
                }
                // Some OEMs format button text as "2\nABC" or desc as "2, ABC"
                if (text.startsWith("$digit\n") || desc.startsWith("$digit,") || desc.startsWith("$digit ")) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findKeypadActionNode(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
                val viewId = node.viewIdResourceName?.lowercase()?.substringAfterLast("/") ?: ""
                if (keywords.any { k -> viewId == k || viewId.contains(k) || text == k || desc == k || desc.contains(k) }) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun performSystemAction(action: String): String {
        val code = when (action.lowercase().trim()) {
            "back", "orqaga" -> GLOBAL_ACTION_BACK
            "home", "bosh_ekran", "uy" -> GLOBAL_ACTION_HOME
            "recents", "oxirgi_ilovalar" -> GLOBAL_ACTION_RECENTS
            "notifications", "bildirishnomalar" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "tezkor_sozlamalar" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot", "skrinshot" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT
                else return "Skrinshot Android 9 va undan yuqori versiyalarda ishlaydi."
            }
            else -> return "Noma'lum tizim harakati: $action"
        }
        val ok = performGlobalAction(code)
        return if (ok) "Tizim harakati ($action) bajarildi." else "Tizim harakatini bajarib bo'lmadi."
    }

    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo,
        query: String,
        requireEditable: Boolean = false
    ): AccessibilityNodeInfo? {
        val q = query.lowercase().trim()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var partialMatch: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase()?.substringAfterLast("/") ?: ""

            val validType = !requireEditable || node.isEditable
            if (validType && node.isVisibleToUser) {
                if (text == q || desc == q || viewId == q) {
                    return node
                }
                if (partialMatch == null && (text.contains(q) || desc.contains(q) || viewId.contains(q))) {
                    partialMatch = node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return partialMatch
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun swipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        if (!dispatched) return false
        return withTimeoutOrNull(2000L) { deferred.await() } ?: false
    }
}
