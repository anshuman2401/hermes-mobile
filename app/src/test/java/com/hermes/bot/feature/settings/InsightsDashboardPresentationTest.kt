package com.hermes.bot.feature.settings

import com.hermes.bot.data.insights.DailyUsage
import com.hermes.bot.data.insights.Metric
import com.hermes.bot.data.insights.UsageRow
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class InsightsDashboardPresentationTest {
    @Test fun healthIsOnlyTakenFromHostOverall() {
        assertEquals("Healthy.", InsightsDashboardPresentation.overallTitle("ok"))
        assertEquals("Degraded.", InsightsDashboardPresentation.overallTitle("degraded"))
        assertEquals("Unavailable", InsightsDashboardPresentation.overallTitle(null))
        assertEquals("Unavailable", InsightsDashboardPresentation.overallTitle("connected"))
    }
    @Test fun resourceRatiosRequireUsableValuesAndKeepZero() {
        assertEquals(0f, InsightsDashboardPresentation.ratio(0.0, 8.0)!!, 0f)
        assertEquals(0.4f, InsightsDashboardPresentation.ratio(3.2, 8.0)!!, 0.00001f)
        assertEquals(1f, InsightsDashboardPresentation.ratio(8.0, 8.0)!!, 0f)
        listOf(null to 8.0, 3.2 to null, 0.0 to 0.0, 9.0 to 8.0, -1.0 to 8.0,
            Double.NaN to 8.0, 1.0 to Double.POSITIVE_INFINITY).forEach { (used, total) ->
            assertNull(InsightsDashboardPresentation.ratio(used, total))
        }
    }
    @Test fun modelBarsUseCompleteCountsWithoutOverflowOrFakeMissingShares() {
        val rows = listOf(UsageRow("first", 24000.0), UsageRow("second", 8000.0))
        assertEquals(listOf(0.75f, 0.25f), InsightsDashboardPresentation.modelShares(rows))
        assertEquals(listOf(0f, 0f), InsightsDashboardPresentation.modelShares(rows.map { it.copy(count = 0.0) }))
        assertEquals(listOf(null, null), InsightsDashboardPresentation.modelShares(rows.mapIndexed { i, r -> if (i == 0) r.copy(count = null) else r }))
        assertEquals(listOf(0.5f, 0.5f), InsightsDashboardPresentation.modelShares(rows.map { it.copy(count = Double.MAX_VALUE) }))
        assertEquals(listOf(null), InsightsDashboardPresentation.modelShares(listOf(UsageRow("bad", -1.0))))
        assertEquals(listOf(null), InsightsDashboardPresentation.modelShares(listOf(UsageRow("bad", Double.NaN))))
        assertTrue(InsightsDashboardPresentation.modelShares(emptyList()).isEmpty())
    }
    @Test fun dailyStacksRemainBoundedAndMissingPartsAreNotZero() {
        val bars = InsightsDashboardPresentation.dailyBars(listOf(
            DailyUsage("2026-10-01", Double.MAX_VALUE, Double.MAX_VALUE),
            DailyUsage("2026-10-02", null, 0.0), DailyUsage("2026-10-03", 0.0, null),
            DailyUsage("2026-10-04", null, null)))
        assertTrue(bars[0].input!! > 0f)
        assertEquals(bars[0].input!!, bars[0].output!!, 0f)
        assertNull(bars[1].input); assertEquals(0f, bars[1].output!!, 0f)
        assertEquals(0f, bars[2].input!!, 0f); assertNull(bars[2].output)
        assertNull(bars[3].input); assertNull(bars[3].output)
        assertTrue(bars.all { (it.input ?: 0f) + (it.output ?: 0f) <= 1f })
        assertTrue(InsightsDashboardPresentation.dailyBars(emptyList()).isEmpty())
    }
    @Test fun systemGroupsPreserveLabelsNullsUnitsAndUnpairedTotals() {
        val metrics = listOf(Metric("Host uptime", 90000.0, "seconds"), Metric("CPU", null, "%"),
            Metric("Memory used", null, "bytes"), Metric("Memory total", 8.0, "bytes"), Metric("Disk total", 256.0, "bytes"))
        val tiles = InsightsDashboardPresentation.systemTiles(metrics)
        assertEquals(listOf("CPU", "Host uptime", "Memory used", "Disk total"), tiles.map { it.metric.label })
        assertEquals(metrics[2], tiles[2].metric)
        assertEquals(metrics[3], tiles[2].total)
        assertNull(tiles[2].proportion)
        assertEquals(metrics.toSet(), tiles.flatMap { listOfNotNull(it.metric, it.total) }.toSet())
    }
    @Test fun missingUsageRemainsUnavailableRatherThanZero() {
        assertNull(InsightsDashboardPresentation.metric(emptyList(), "Estimated cost", "USD").value)
        assertEquals("USD", InsightsDashboardPresentation.metric(emptyList(), "Estimated cost", "USD").unit)
        val zero = Metric("Input tokens", 0.0)
        assertSame(zero, InsightsDashboardPresentation.metric(listOf(zero), "Input tokens"))
    }
    @Test fun narrowScreensAndLargeFontsStackTiles() {
        assertEquals(2, InsightsDashboardPresentation.columns(346f, 1f))
        assertEquals(1, InsightsDashboardPresentation.columns(276f, 1f))
        assertEquals(1, InsightsDashboardPresentation.columns(346f, 1.3f))
        assertEquals(1, InsightsDashboardPresentation.columns(800f, 2f))
    }
    @Test fun tokenTotalsUseOnlyMillionAndBillionSuffixes() {
        val examples = mapOf(0.0 to "0", 250000.0 to "250,000", 999999.0 to "999,999",
            1000000.0 to "1M", 260051162.0 to "260.05M", 24193303.0 to "24.19M",
            1234567890.0 to "1.23B", 999999999.0 to "1B")
        examples.forEach { (value, expected) ->
            assertEquals(expected, InsightsDashboardPresentation.formatTokens(value, Locale.US))
        }
        assertEquals("1,25M", InsightsDashboardPresentation.formatTokens(1250000.0, Locale.GERMANY))
        assertEquals("250.000", InsightsDashboardPresentation.formatTokens(250000.0, Locale.GERMANY))
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals("Unavailable", InsightsDashboardPresentation.formatTokens(it, Locale.US))
        }
        assertEquals(listOf("Input tokens", "Output tokens", "Cache read tokens"), InsightsDashboardPresentation.heroTokenLabels)
    }

    @Test fun dailyAxisAndStacksUseTheSameRoundedScale() {
        val chart = InsightsDashboardPresentation.dailyChart(listOf(
            DailyUsage("2026-10-01", 6000000.0, 2000000.0),
            DailyUsage("2026-10-02", 2000000.0, 1000000.0)))
        assertEquals(listOf(0L, 2000000L, 4000000L, 6000000L, 8000000L), chart.ticks.map { it.toLong() })
        assertEquals(0.75f, chart.bars[0].input!!, 0f)
        assertEquals(0.25f, chart.bars[0].output!!, 0f)
        assertEquals(0.25f, chart.bars[1].input!!, 0f)
        assertEquals(0.125f, chart.bars[1].output!!, 0f)
        val rounded = InsightsDashboardPresentation.dailyChart(listOf(DailyUsage("2026-10-01", 8100000.0, 0.0)))
        assertEquals(20000000L, rounded.ticks.last().toLong())
        assertEquals(0.405f, rounded.bars.single().input!!, 0.000001f)
    }

    @Test fun dailyAxisHandlesEmptyZeroMissingAndExtremeCounts() {
        assertTrue(InsightsDashboardPresentation.dailyChart(emptyList()).bars.isEmpty())
        val zero = InsightsDashboardPresentation.dailyChart(listOf(DailyUsage("2026-10-01", 0.0, 0.0)))
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), zero.ticks.map { it.toLong() })
        assertEquals(InsightsDashboardPresentation.DailyBar(0f, 0f), zero.bars.single())
        val invalid = InsightsDashboardPresentation.dailyChart(listOf(DailyUsage("2026-10-01", Double.NaN, -1.0)))
        assertEquals(InsightsDashboardPresentation.DailyBar(null, null), invalid.bars.single())
        val chart = InsightsDashboardPresentation.dailyChart(listOf(DailyUsage("2026-10-01", Double.MAX_VALUE, Double.MAX_VALUE)))
        assertTrue(chart.ticks.last() >= BigDecimal.valueOf(Double.MAX_VALUE) * BigDecimal(2))
        assertEquals(5, chart.ticks.size)
        assertTrue(chart.ticks.zipWithNext().all { (a, b) -> b > a })
        val bar = chart.bars.single()
        assertTrue(bar.input!!.isFinite() && bar.output!!.isFinite())
        assertTrue(bar.input!! + bar.output!! <= 1f)
    }

    @Test fun dailyBarsStayVisibleInNarrowPlotsForEveryPeriod() {
        listOf(7, 30, 90).forEach { days ->
            val slot = 180f / days
            val width = InsightsDashboardPresentation.dailyBarWidth(slot, 3f)
            assertTrue(width > 0f && width <= slot)
        }
        assertEquals(0f, InsightsDashboardPresentation.dailyBarWidth(0f, 3f), 0f)
    }

}
