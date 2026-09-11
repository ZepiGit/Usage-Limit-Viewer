package com.usagelimits.core.time

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Turns the assorted timestamp shapes providers emit into epoch millis.
 *
 * Between the four providers this app sees ISO-8601 with and without offsets, epoch seconds,
 * epoch millis, and relative "seconds from now" offsets — sometimes for the same concept on
 * different endpoints. Everything funnels through here so the rest of the app deals only in
 * absolute epoch millis.
 */
object Instants {

    /** Epoch seconds beyond this are already millis. Roughly the year 2286. */
    private const val SECONDS_UPPER_BOUND = 10_000_000_000L

    /** Parses an absolute instant from a string or number. Returns null if unusable. */
    fun parse(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { return fromEpochNumber(it) }
        // Finite only: "Infinity".toDouble() is +Inf, whose toLong() saturates to Long.MAX and
        // would have made a reset in the year 292 million the snapshot's "next reset".
        raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return fromEpochNumber(it.toLong()) }

        // Trim sub-millisecond precision, which java.time accepts but some providers overrun.
        val normalized = raw.replace(Regex("(\\.\\d{3})\\d+"), "$1")
        runCatching { return OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(normalized).toEpochMilli() }
        return try {
            OffsetDateTime.parse("${normalized}Z").toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Disambiguates epoch seconds from epoch millis by magnitude. */
    fun fromEpochNumber(value: Long): Long? {
        if (value <= 0) return null
        return if (value < SECONDS_UPPER_BOUND) value * 1000 else value
    }

    /** Converts a "resets in N seconds" offset into an absolute instant. */
    fun fromOffsetSeconds(seconds: Long?, nowMs: Long): Long? {
        if (seconds == null || seconds < 0) return null
        // Bounded before the multiply, not after. `seconds * 1000` overflows silently in Kotlin
        // and lands in the past, which is the worst direction: a reset shown as already done,
        // and — because the account's next reset is a min over every window — one bogus value
        // dragging the whole account's clock backwards.
        //
        // The bound is the same figure that separates a seconds stamp from a millis one, about
        // three centuries. Anything past it is not a duration a quota window has; refusing it
        // says "no reset time" rather than inventing one.
        if (seconds > SECONDS_UPPER_BOUND) return null
        return nowMs + seconds * 1000
    }
}
