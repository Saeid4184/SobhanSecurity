package ir.factory.entryexit.util

import android.content.Context
import ir.factory.entryexit.data.CloudSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Same local-then-cloud key lookup [ir.factory.entryexit.ui.ReportActivity] already does before
 * running the "تحلیل هوشمند" report analysis, factored out here so the personnel-photo and
 * plate-detection features (SetupActivity) pick up a key already entered on another device or
 * the web admin panel without asking the guard to type it in a second time.
 *
 * Returns null (never throws) if no key is available anywhere — callers should show the same
 * "کلید API تنظیم نشده" prompt the report screen uses.
 */
object AiKeyHelper {
    suspend fun resolveApiKey(context: Context): String? {
        var key = AppPreferences.getAiApiKey(context)
        if (key.isBlank()) {
            val cloudKey = withContext(Dispatchers.IO) { CloudSettings.fetchAiApiKey() }
            if (!cloudKey.isNullOrBlank()) {
                key = cloudKey
                AppPreferences.setAiApiKey(context, cloudKey)
            }
        }
        return key.ifBlank { null }
    }
}
