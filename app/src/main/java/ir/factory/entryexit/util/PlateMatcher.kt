package ir.factory.entryexit.util

import ir.factory.entryexit.data.PersonEntity

/**
 * Matches a plate's digits (as read by [GeminiVisionClient.detectLicensePlate]) against the
 * machinery roster. Vehicle records store their plate as part of [PersonEntity.name] (see
 * [ir.factory.entryexit.data.Fleet], e.g. "پلاک ۶۹۷۴۴") rather than in a dedicated column, so
 * matching is done purely on the digits — ignoring the "پلاک" prefix, spacing, and
 * Persian-vs-English digit form.
 */
object PlateMatcher {
    fun findMatch(detectedDigits: String, roster: List<PersonEntity>): PersonEntity? {
        if (detectedDigits.isBlank()) return null
        return roster.firstOrNull { it.name.extractDigits() == detectedDigits }
    }
}
