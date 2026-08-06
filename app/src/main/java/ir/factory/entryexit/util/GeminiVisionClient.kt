package ir.factory.entryexit.util

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Two Gemini vision features, both riding the same v1beta generateContent endpoint and the same
 * user-supplied free API key as [AiReportAnalyzer] (Settings screen; never bundled with the app):
 *
 *  - [generatePersonnelPhoto]: image-in / image-out editing on gemini-2.5-flash-image (aka
 *    "Nano Banana") — turns a snapshot into a neat, uniform personnel/ID photo. The prompt is
 *    written to be as strict as possible that the face itself must not change.
 *  - [detectLicensePlate]: image-in / text-out reading on gemini-flash-latest (the same
 *    auto-updating text model AiReportAnalyzer already uses) — pulls just the plate's digits
 *    out of a vehicle photo.
 *
 * NOTE on model choice: gemini-2.5-flash-image is Google's current stable, free-tier image
 * generation/editing model (as opposed to the newer/pricier "-preview" Nano Banana 2/Pro
 * models, which don't have a free tier). If Google retires this one the way it retired
 * gemini-1.5-flash, swap IMAGE_MODEL here — same single-line fix as AiReportAnalyzer's own note.
 */
object GeminiVisionClient {

    private const val IMAGE_MODEL = "gemini-2.5-flash-image"
    private const val VISION_TEXT_MODEL = "gemini-flash-latest"
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private const val CONNECT_TIMEOUT_MS = 25_000
    private const val READ_TIMEOUT_MS = 45_000

    /** Sends [imageBytes] to Gemini for editing into a formal personnel photo. Returns the new
     *  image's raw bytes on success. */
    fun generatePersonnelPhoto(apiKey: String, imageBytes: ByteArray, mimeType: String): Result<ByteArray> {
        if (apiKey.isBlank()) return Result.failure(missingKeyError())

        val prompt = """
            You are retouching a photo for an official staff ID badge at a concrete factory.
            ABSOLUTE RULE — DO NOT CHANGE THE PERSON: keep the face, facial features, face shape,
            skin tone/color, expression, hairstyle, and overall facial identity 100% identical to
            the original photo, with zero alteration. Do not beautify, do not smooth skin, do not
            change age, weight, or any facial proportion. The person must remain perfectly
            recognizable as the exact same individual.
            WHAT TO CHANGE: replace the background with a plain, neutral, evenly-lit studio
            backdrop (light gray or soft blue), correct the framing/crop to a standard
            head-and-shoulders official ID/passport-style portrait, straighten the horizon if
            tilted, and improve overall lighting/sharpness so it looks like a proper
            administrative personnel photo. Keep it photorealistic — this is a real person's ID
            photo, not an illustration or a different person.

            یک قانون قطعی و غیرقابل تغییر: چهره، جزئیات صورت، فرم صورت، رنگ پوست و کل ترکیب صورت
            شخص باید ۱۰۰ درصد دقیقاً همانند عکس اصلی و بدون کوچک‌ترین تغییر باقی بماند؛ فقط
            پس‌زمینه تصویر را به یک پس‌زمینه ساده، یک‌دست و استودیویی تبدیل کن، قاب/برش تصویر را
            به‌شکل استاندارد یک عکس پرسنلی/اداری (سر و شانه) تنظیم کن و نور و وضوح تصویر را برای
            ظاهری اداری و مرتب بهبود بده.
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().apply {
                                        put("mimeType", mimeType)
                                        put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                                    }
                                )
                            )
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            )
        }

        return try {
            val responseText = post(IMAGE_MODEL, apiKey, requestBody)
            val imageBase64 = extractInlineImage(responseText)
                ?: return Result.failure(imageMissingError(responseText))
            Result.success(Base64.decode(imageBase64, Base64.NO_WRAP))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(IllegalStateException("اتصال اینترنت برقرار نیست."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("پاسخ سرویس هوش مصنوعی بیش از حد طول کشید. دوباره تلاش کنید."))
        } catch (e: Exception) {
            Result.failure(exceptionOrWrap(e))
        }
    }

    /** Returns just the plate's digits (e.g. "69744"), or Result.success(null) if no plate was
     *  readable in the photo. */
    fun detectLicensePlate(apiKey: String, imageBytes: ByteArray, mimeType: String): Result<String?> {
        if (apiKey.isBlank()) return Result.failure(missingKeyError())

        val prompt = """
            Look at this photo of a truck/vehicle at a concrete factory gate. Find the vehicle's
            license plate and reply with ONLY the digits of the plate number, nothing else — no
            words, no spaces, no punctuation, no explanation. If you cannot find or confidently
            read a plate, reply with exactly: NONE
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().apply {
                                        put("mimeType", mimeType)
                                        put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                                    }
                                )
                            )
                    )
                )
            )
        }

        return try {
            val responseText = post(VISION_TEXT_MODEL, apiKey, requestBody)
            val text = extractText(responseText)?.trim().orEmpty()
            val digits = Regex("\\d+").findAll(text).joinToString("") { it.value }
            Result.success(digits.ifBlank { null })
        } catch (e: java.net.UnknownHostException) {
            Result.failure(IllegalStateException("اتصال اینترنت برقرار نیست."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("پاسخ سرویس هوش مصنوعی بیش از حد طول کشید. دوباره تلاش کنید."))
        } catch (e: Exception) {
            Result.failure(exceptionOrWrap(e))
        }
    }

    private fun post(model: String, apiKey: String, body: JSONObject): String {
        val url = URL(ENDPOINT_TEMPLATE.format(model, apiKey.trim()))
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS

        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

        if (responseCode !in 200..299) {
            throw errorForCode(responseCode, responseText)
        }
        return responseText
    }

    private fun exceptionOrWrap(e: Exception): Exception =
        if (e.message?.startsWith("خطا") == true || e is IllegalStateException || e is IllegalArgumentException) {
            e
        } else {
            IllegalStateException("خطا در ارتباط با سرویس هوش مصنوعی: ${e.message}")
        }

    private fun errorForCode(code: Int, body: String): Exception {
        val message = when (code) {
            400 -> "درخواست نامعتبر بود (احتمالاً کلید API اشتباه است)."
            401, 403 -> "کلید API نامعتبر است یا دسترسی ندارد. آن را در تنظیمات بررسی کنید."
            429 -> "سقف مجاز درخواست‌های رایگان امروز پر شده؛ کمی بعد دوباره امتحان کنید."
            in 500..599 -> "سرویس هوش مصنوعی موقتاً در دسترس نیست."
            else -> "خطای غیرمنتظره ($code) از سرویس هوش مصنوعی."
        }
        return IllegalStateException(message)
    }

    private fun missingKeyError() =
        IllegalArgumentException("کلید API هوش مصنوعی تنظیم نشده است. از صفحه تنظیمات وارد کنید.")

    private fun imageMissingError(responseJson: String): Exception {
        val blockReason = runCatching {
            JSONObject(responseJson).optJSONObject("promptFeedback")?.optString("blockReason")
        }.getOrNull()
        return if (!blockReason.isNullOrBlank()) {
            IllegalStateException("سرویس هوش مصنوعی این عکس را پردازش نکرد (دلیل: $blockReason). عکس دیگری امتحان کنید.")
        } else {
            IllegalStateException("سرویس هوش مصنوعی نتوانست عکس جدید بسازد. دوباره تلاش کنید یا عکس دیگری انتخاب کنید.")
        }
    }

    private fun extractInlineImage(responseJson: String): String? = try {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates")
        var found: String? = null
        if (candidates != null && candidates.length() > 0) {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                    val data = inlineData?.optString("data")
                    if (!data.isNullOrBlank()) {
                        found = data
                        break
                    }
                }
            }
        }
        found
    } catch (e: Exception) {
        null
    }

    private fun extractText(responseJson: String): String? = try {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            null
        } else {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text", ""))
                }
            }
            sb.toString()
        }
    } catch (e: Exception) {
        null
    }
}
