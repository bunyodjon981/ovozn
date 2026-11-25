package com.example.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.service.PhoneControlAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineActionOutcome(
    val replyUzbek: String,
    val actionTag: String
)

class OfflineCommandEngine(
    private val context: Context,
    private val phoneCapabilities: PhoneCapabilities
) {

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun tryExecuteOffline(rawCommand: String, savedUnlockPin: String): OfflineActionOutcome? {
        val text = rawCommand.trim()
        val lower = text.lowercase()
            .replace("oʻ", "o'")
            .replace("gʻ", "g'")
            .replace("ʼ", "'")
            .replace("’", "'")

        // 1. Lock screen / Phone Unlock commands (offline!)
        if (lower.contains("qulfdan och") ||
            lower.contains("blokdan och") ||
            lower.contains("blokni och") ||
            lower.contains("qulfni och") ||
            lower == "ekranni och" ||
            lower == "telefonni och" ||
            lower == "telefonimni och"
        ) {
            val spokenDigits = text.replace(Regex("[^0-9]"), "")
            val pinToUse = if (spokenDigits.length >= 4) spokenDigits else savedUnlockPin
            val acc = PhoneControlAccessibilityService.instance
            val msg = if (acc != null) {
                acc.unlockDeviceWithPin(pinToUse)
            } else {
                "Qulfdan ochish uchun avval Ruxsatlar bo'limidan Ekran nazoratini yoqing."
            }
            return OfflineActionOutcome(msg, "offline_unlock")
        }

        // 2. Flashlight (offline)
        if (lower.contains("chiroq") || lower.contains("flesh") || lower.contains("fonar")) {
            val turnOn = !lower.contains("o'chir") && !lower.contains("ochir")
            val msg = phoneCapabilities.toggleFlashlight(turnOn)
            return OfflineActionOutcome(msg, "offline_flashlight")
        }

        // 3. Volume control (offline)
        if (lower.contains("ovoz")) {
            val action = when {
                lower.contains("ko'tar") || lower.contains("kotar") || lower.contains("oshir") || lower.contains("baland") -> "up"
                lower.contains("pasay") || lower.contains("tushir") || lower.contains("past") -> "down"
                lower.contains("o'chir") || lower.contains("ochir") || lower.contains("jim") -> "mute"
                lower.contains("maks") || lower.contains("to'liq") || lower.contains("toliq") -> "max"
                else -> null
            }
            if (action != null) {
                val msg = phoneCapabilities.controlVolume(action)
                return OfflineActionOutcome(msg, "offline_volume:$action")
            }
        }

        // 4. Time & Date (offline)
        if (lower.contains("soat nech") || lower == "vaqt" || lower.contains("hozir soat")) {
            val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            return OfflineActionOutcome("Hozir soat $now.", "offline_time")
        }
        if (lower.contains("bugun qaysi kun") || lower.contains("bugungi sana") || lower.contains("nechanchi sana")) {
            val today = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
            return OfflineActionOutcome("Bugungi sana: $today.", "offline_date")
        }

        // 5. Alarm (offline)
        if (lower.contains("budilnik")) {
            val nums = Regex("\\d{1,2}").findAll(lower).map { it.value.toInt() }.toList()
            if (nums.isNotEmpty()) {
                val h = nums[0].coerceIn(0, 23)
                val m = nums.getOrElse(1) { 0 }.coerceIn(0, 59)
                val msg = phoneCapabilities.setAlarm(h, m, "Telefonim AI")
                return OfflineActionOutcome(msg, "offline_alarm")
            }
        }

        // 6. System navigation (offline)
        when {
            lower == "orqaga" || lower == "orqaga qayt" -> {
                val msg = PhoneControlAccessibilityService.instance?.performSystemAction("back")
                    ?: "Orqaga qaytish uchun Ekran nazoratini yoqing."
                return OfflineActionOutcome(msg, "offline_back")
            }
            lower.contains("bosh ekran") || lower == "uyga qayt" -> {
                val msg = PhoneControlAccessibilityService.instance?.performSystemAction("home")
                    ?: "Bosh ekranga chiqish uchun Ekran nazoratini yoqing."
                return OfflineActionOutcome(msg, "offline_home")
            }
            lower.contains("skrinshot") || lower.contains("ekran rasmi") -> {
                val msg = PhoneControlAccessibilityService.instance?.performSystemAction("screenshot")
                    ?: "Skrinshot olish uchun Ekran nazoratini yoqing."
                return OfflineActionOutcome(msg, "offline_screenshot")
            }
            lower.contains("bildirishnoma") -> {
                val msg = PhoneControlAccessibilityService.instance?.performSystemAction("notifications")
                    ?: "Bildirishnomalarni ochish uchun Ekran nazoratini yoqing."
                return OfflineActionOutcome(msg, "offline_notifications")
            }
        }

        // 7. System Settings shortcuts (offline)
        if (lower.contains("sozlama") || lower.contains("nastroyka")) {
            val section = when {
                lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                lower.contains("bluetooth") || lower.contains("blutuz") -> "bluetooth"
                lower.contains("ovoz") -> "sound"
                lower.contains("ekran") -> "display"
                lower.contains("batareya") -> "battery"
                else -> "general"
            }
            val msg = phoneCapabilities.openSystemSettings(section)
            return OfflineActionOutcome(msg, "offline_settings:$section")
        }

        // 8. Phone Call (offline): "<contact/number> ga qo'ng'iroq qil / telefon qil"
        if ((lower.contains("qo'ng'iroq") || lower.contains("qongiroq") || lower.contains("telefon qil") || lower.contains("zvanok")) &&
            !lower.contains("telegramda") && !lower.contains("whatsappda")
        ) {
            val cleanedTarget = lower
                .replace(Regex("(qo'ng'iroq|qongiroq|telefon|zvanok)\\s*(qil|qiling|ber|et)?"), "")
                .replace(Regex("\\s*(raqamiga|raqamga|ga|ka|qa)\\s*$"), "")
                .trim()
            if (cleanedTarget.isNotBlank()) {
                val msg = phoneCapabilities.makePhoneCall(cleanedTarget)
                return OfflineActionOutcome(msg, "offline_call")
            }
        }

        // 9. Direct SMS (offline): "<contact> ga <message> deb yubor / sms yoz"
        if ((lower.contains("deb yubor") || lower.contains("deb yoz") || lower.contains("sms")) &&
            !lower.contains("telegram") && !lower.contains("whatsapp") && !lower.contains("insta")
        ) {
            val smsRegex = Regex("^(.+?)\\s+(?:ga|ka|qa)\\s+(.+?)\\s+(?:deb\\s+(?:yubor|yoz|ayt)|sms\\s+(?:yubor|yoz)).*$")
            val match = smsRegex.find(lower)
            if (match != null) {
                val recipient = match.groupValues[1].trim()
                val message = match.groupValues[2].trim()
                if (recipient.isNotBlank() && message.isNotBlank()) {
                    val msg = phoneCapabilities.sendSms(recipient, message.replaceFirstChar { it.uppercase() })
                    return OfflineActionOutcome(msg, "offline_sms")
                }
            }
        }

        // 10. Single-step App Opening (offline): "<App> ni och" / "och <App>"
        val isMultiStepInApp = lower.contains("telegramda") || lower.contains("whatsappda") ||
            lower.contains("ichida") || lower.contains("yozib") || lower.contains("qidir")
        if (!isMultiStepInApp && (lower.endsWith(" och") || lower.endsWith("ni och") || lower.startsWith("och ") || lower.contains("ishga tushir"))) {
            val appQuery = lower
                .removePrefix("och ")
                .replace(Regex("(ni\\s+och|och|ilovani\\s+och|ilovasini\\s+och|ishga\\s+tushir)$"), "")
                .trim()
            if (appQuery.isNotBlank() && !appQuery.contains("ekran")) {
                val msg = phoneCapabilities.openApp(appQuery)
                return OfflineActionOutcome(msg, "offline_open_app:$appQuery")
            }
        }

        return null
    }
}
