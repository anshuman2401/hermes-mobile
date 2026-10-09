package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.insights.DailyUsage
import io.github.anshuman2401.hermesmobile.data.insights.Metric
import io.github.anshuman2401.hermesmobile.data.insights.UsageRow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Pure presentation of existing metrics; it neither fetches data nor invents missing values. */
internal object InsightsDashboardPresentation {
    fun overallTitle(overall: String?): String = when (overall) {
        "ok" -> "Healthy."
        "degraded" -> "Degraded."
        else -> "Unavailable"
    }

    fun columns(contentWidthDp: Float, fontScale: Float): Int =
        if (contentWidthDp < 300f || fontScale > 1.2f) 1 else 2

    fun ratio(used: Double?, total: Double?): Float? {
        if (used == null || total == null || !used.isFinite() || !total.isFinite() ||
            used < 0.0 || total <= 0.0 || used > total) return null
        return (used / total).toFloat()
    }

    /** An incomplete model breakdown cannot claim proportions of all model tokens. */
    fun modelShares(rows: List<UsageRow>): List<Float?> {
        if (rows.isEmpty()) return emptyList()
        if (rows.any { it.count == null || !it.count.isFinite() || it.count < 0.0 }) return List(rows.size) { null }
        val max = rows.maxOf { it.count!! }
        if (max == 0.0) return List(rows.size) { 0f }
        // Normalize before summing so large finite counts cannot overflow the denominator.
        val scaled = rows.map { it.count!! / max }
        val sum = scaled.sum()
        return scaled.map { (it / sum).toFloat() }
    }

    data class DailyBar(val input: Float?, val output: Float?)

    data class DailyChart(val bars: List<DailyBar>, val ticks: List<BigDecimal>)

    private val million = BigDecimal("1000000")
    private val billion = BigDecimal("1000000000")
    val heroTokenLabels = listOf("Input tokens", "Output tokens", "Cache read tokens")

    fun formatTokens(value: Double?, locale: Locale): String =
        value?.takeIf { it.isFinite() && it >= 0.0 }?.let { formatTokens(BigDecimal.valueOf(it), locale) }
            ?: "Unavailable"

    fun formatTokens(value: BigDecimal, locale: Locale): String {
        if (value < million) return NumberFormat.getIntegerInstance(locale).format(value)
        // Promote values which round to a billion instead of displaying 1000M.
        val inBillions = value >= billion || value.divide(million, 2, RoundingMode.HALF_UP) >= BigDecimal("1000")
        val scaled = value.divide(if (inBillions) billion else million, 2, RoundingMode.HALF_UP)
        return NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 2
            isGroupingUsed = false
        }.format(scaled) + if (inBillions) "B" else "M"
    }

    /** One shared token scale for ticks and stacks, including sums exceeding Double's range. */
    fun dailyChart(days: List<DailyUsage>): DailyChart {
        fun known(value: Double?) = value?.takeIf { it.isFinite() && it >= 0.0 }?.let(BigDecimal::valueOf)
        val values = days.map { known(it.input) to known(it.output) }
        val largest = values.maxOfOrNull { (it.first ?: BigDecimal.ZERO) + (it.second ?: BigDecimal.ZERO) }
            ?: BigDecimal.ZERO
        val target = largest.divide(BigDecimal(4))
        val magnitude = if (target.signum() == 0) BigDecimal.ONE
            else BigDecimal.ONE.scaleByPowerOfTen(target.precision() - target.scale() - 1)
        val step = listOf(1, 2, 5, 10).map { magnitude * BigDecimal(it) }
            .first { it >= target }.max(BigDecimal.ONE)
        val top = step * BigDecimal(4)
        val bars = values.map { (input, output) ->
            DailyBar(input?.divide(top, MathContext.DECIMAL64)?.toFloat(),
                output?.divide(top, MathContext.DECIMAL64)?.toFloat())
        }
        return DailyChart(bars, (0..4).map { step * BigDecimal(it) })
    }

    /** Dense periods retain visible bars instead of losing the entire slot to a fixed gap. */
    fun dailyBarWidth(slot: Float, preferredGap: Float): Float =
        (slot - minOf(preferredGap, slot * 0.2f)).coerceIn(0f, slot)

    fun dailyBars(days: List<DailyUsage>): List<DailyBar> = dailyChart(days).bars

    fun metric(metrics: List<Metric>, label: String, unit: String = ""): Metric =
        metrics.firstOrNull { it.label == label } ?: Metric(label, null, unit)

    data class SystemTile(val metric: Metric, val total: Metric? = null) {
        val proportion: Float? get() = ratio(metric.value, total?.value)
    }

    fun systemTiles(metrics: List<Metric>): List<SystemTile> {
        val ordered = listOf("CPU", "Host uptime", "Memory used", "Disk used")
        val consumed = mutableSetOf<String>()
        val tiles = ordered.mapNotNull { label ->
            val metric = metrics.firstOrNull { it.label == label } ?: return@mapNotNull null
            consumed.add(label)
            val totalLabel = when (label) { "Memory used" -> "Memory total"; "Disk used" -> "Disk total"; else -> null }
            val total = metrics.firstOrNull { it.label == totalLabel }
            total?.let { consumed.add(it.label) }
            SystemTile(metric, total)
        }
        return tiles + metrics.filter { it.label !in consumed }.map { SystemTile(it) }
    }
}
