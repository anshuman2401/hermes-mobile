package com.hermes.bot.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.bot.R
import com.hermes.bot.data.insights.*
import com.hermes.bot.design.RelayOrbitIcon
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal val InsightsHeroLight = Color(0xFFEAF0E7)
internal val InsightsHeroDark = Color(0xFF263429)

/** Approved editorial dashboard; page ownership and all reads stay in the existing route. */
@Composable
internal fun HealthAnalyticsScreen(
    page: HostInsightsPage,
    state: HostInsightsUiState,
    back: () -> Unit,
    refresh: () -> Unit,
    period: (Int) -> Unit,
    selectProfile: (String) -> Unit = {},
    preview: Boolean = false
) {
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate back")
                }
                Spacer(Modifier.weight(1f))
                RelayOrbitIcon(size = 18.dp)
                Spacer(Modifier.width(7.dp))
                Text("HERMES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
                Spacer(Modifier.weight(1f))
                if (!preview) IconButton(onClick = refresh, enabled = !state.loading, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, "Refresh host information")
                } else Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.height(20.dp))
            EditorialText(page.title, 48, 52, Modifier.semantics { heading() })
            Text(if (page == HostInsightsPage.HEALTH) "Host, gateway & system." else "Usage, tokens & costs.",
                Modifier.padding(top = 8.dp, bottom = 22.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AdaptivePair {
                Text(state.profile?.let { if (page == HostInsightsPage.ANALYTICS) "Viewing profile: $it" else "Profile: $it" }
                    ?: if (page == HostInsightsPage.ANALYTICS) "No profile selected" else "No selected profile",
                    style = MaterialTheme.typography.titleSmall)
                state.fetchedAt?.let {
                    Caption("Fetched ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (preview) item { InsightsNotice("Host information unavailable in preview mode.") }
        if (page == HostInsightsPage.ANALYTICS && !preview) item {
            AnalyticsProfilePicker(state.analyticsProfiles, state.profile, selectProfile)
            Spacer(Modifier.height(16.dp))
            PeriodChoices(state.days, period)
            Spacer(Modifier.height(16.dp))
        }
        if (page == HostInsightsPage.ANALYTICS && state.analyticsRosterEmpty) item {
            InsightsNotice("No profiles are available for Analytics.", refresh, state.loading)
        }
        if (state.loading) item {
            Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption("Loading host information…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.failure?.let { failure -> item { InsightsNotice(failure.message, refresh, state.loading) } }
        if (page == HostInsightsPage.HEALTH) state.health?.let { health ->
            val status = health.status
            when (status) {
                is InsightsRead.Failed -> item {
                    DashboardSection("Host and gateway")
                    InsightsNotice(status.reason.message, refresh, state.loading)
                }
                is InsightsRead.Ready -> item {
                    HealthHero(status.value)
                    Spacer(Modifier.height(16.dp))
                    MetricGrid(listOf(Metric("Active sessions", status.value.sessions), Metric("Active agents", status.value.agents)))
                }
            }
            item { DashboardSection("Host system", "Machine-wide") }
            when (val system = health.system) {
                is InsightsRead.Failed -> item { InsightsNotice(system.reason.message, refresh, state.loading) }
                is InsightsRead.Ready -> item { SystemGrid(system.value.metrics) }
            }
            if (status is InsightsRead.Ready && status.value.components.isNotEmpty()) {
                item { DashboardSection("Components", "Host-reported") }
                dashboardRows(status.value.components) { (label, value) ->
                    AdaptivePair {
                        Caption(label)
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = statusColor(value))
                    }
                }
            }
        }
        if (page == HostInsightsPage.ANALYTICS) state.usage?.let { usage ->
            item { UsageHero(usage) }
            item {
                Spacer(Modifier.height(16.dp))
                DashboardPanel {
                    Column(Modifier.padding(16.dp)) {
                        DashboardSection("Daily tokens", "Input / output", topPadding = 0)
                        if (usage.daily.isEmpty()) Caption("No daily activity in this period.")
                        else InsightsDailyChart(usage.daily)
                    }
                }
            }
            item { DashboardSection("By model", "Tokens") }
            if (usage.models.isEmpty()) item { InsightsNotice("No data available for this period.") }
            else {
                val shares = InsightsDashboardPresentation.modelShares(usage.models)
                dashboardRows(usage.models.withIndex().toList()) { (index, row) ->
                    ModelBreakdown(row, shares[index], if (index % 2 == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                }
            }
            item {
                DashboardSection("Activity", "Last ${usage.days} days")
                val heroLabels = setOf("Estimated cost", "Actual cost") + InsightsDashboardPresentation.heroTokenLabels
                val activity = usage.totals.filter { it.label !in heroLabels }
                if (activity.isEmpty()) InsightsNotice("No data available for this period.") else MetricGrid(activity)
            }
            listOf("Auxiliary tasks" to ("Tokens" to usage.tasks), "Skills" to ("Actions" to usage.skills),
                "Tools" to ("Calls" to usage.tools)).forEach { (title, breakdown) ->
                val (unit, rows) = breakdown
                item { DashboardSection(title, unit) }
                if (rows.isEmpty()) item { InsightsNotice("No data available for this period.") }
                else dashboardRows(rows) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleSmall)
                        Text(formatMetric(Metric("", row.count)), style = MaterialTheme.typography.bodyLarge)
                        row.cost?.let { Caption("${formatMetric(Metric("", it, "USD"))} estimated") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsProfilePicker(profiles: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Caption("Analytics profile")
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = profiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(selected ?: "No profiles available", modifier = Modifier.weight(1f), maxLines = 1)
                Text("Choose")
            }
            DropdownMenu(expanded = expanded && profiles.isNotEmpty(), onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)) {
                profiles.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile) }, onClick = {
                        expanded = false
                        onSelect(profile)
                    }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun HealthHero(status: HostStatusOverview) {
    DashboardPanel(hero = true) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Overall health", R.drawable.ic_settings_health)
            EditorialText(InsightsDashboardPresentation.overallTitle(status.overall), 40, 46,
                Modifier.semantics { heading() }, color = if (status.overall == "degraded") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Caption("Host-reported status: ${status.overall ?: "Unavailable"}")
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AdaptivePair {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = statusColor(status.gateway)
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Text("Gateway ${status.gateway ?: "Unavailable"}", style = MaterialTheme.typography.titleSmall, color = color)
                }
                Caption("Hermes version · ${status.version ?: "Unavailable"}")
            }
        }
    }
}

@Composable
private fun UsageHero(usage: HostUsage) {
    val locale = LocalConfiguration.current.locales[0]
    val estimated = InsightsDashboardPresentation.metric(usage.totals, "Estimated cost", "USD")
    val actual = InsightsDashboardPresentation.metric(usage.totals, "Actual cost", "USD")
    DashboardPanel(hero = true) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Estimated cost", R.drawable.ic_settings_analytics)
            EditorialText(formatMetric(estimated), if (estimated.value == null) 32 else 44, if (estimated.value == null) 38 else 50)
            Caption("Last ${usage.days} days · Actual cost ${formatMetric(actual)}")
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DashboardGrid(InsightsDashboardPresentation.heroTokenLabels) { label ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(label)
                    EditorialText(InsightsDashboardPresentation.formatTokens(
                        InsightsDashboardPresentation.metric(usage.totals, label).value, locale), 26, 32)
                }
            }
        }
    }
}

@Composable
private fun PeriodChoices(days: Int, period: (Int) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    DashboardPanel {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val segmentWidth = maxOf(92.dp * fontScale, (maxWidth - 16.dp) / 3)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7, 30, 90).forEach { choice ->
                    val selected = days == choice
                    Box(Modifier.width(segmentWidth).defaultMinSize(minHeight = 48.dp)
                        .background(if (selected) heroColor() else Color.Transparent, RoundedCornerShape(8.dp))
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { period(choice) })
                        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text("$choice days", style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<Metric>) {
    DashboardGrid(metrics) { metric ->
        DashboardPanel(modifier = Modifier.fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(metric.label)
                if (metric.value == null) Text("Unavailable", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else EditorialText(formatMetric(metric), 30, 36)
            }
        }
    }
}

@Composable
private fun SystemGrid(metrics: List<Metric>) {
    val tiles = InsightsDashboardPresentation.systemTiles(metrics)
    if (tiles.isEmpty()) InsightsNotice("No data available.")
    else DashboardGrid(tiles) { tile ->
        DashboardPanel(modifier = Modifier.fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val resource = when (tile.metric.label) {
                    "CPU" -> R.drawable.ic_insights_cpu
                    "Host uptime" -> R.drawable.ic_insights_clock
                    "Memory used" -> R.drawable.ic_insights_memory
                    "Disk used" -> R.drawable.ic_insights_disk
                    else -> R.drawable.ic_settings_server
                }
                Eyebrow(tile.metric.label, resource)
                if (tile.metric.value == null) {
                    Text("Unavailable", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Caption("No value reported")
                } else if (tile.metric.unit == "seconds") Text(formatMetric(tile.metric), style = MaterialTheme.typography.titleMedium)
                else EditorialText(formatMetric(tile.metric), 30, 36)
                tile.proportion?.let { Meter(it, MaterialTheme.colorScheme.secondary) }
                tile.total?.let { Caption("${it.label} · ${formatMetric(it)}") }
            }
        }
    }
}

/** Adaptive content, with no fixed card heights or clipped values at increased font size. */
@Composable
private fun <T> DashboardGrid(values: List<T>, content: @Composable (T) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = InsightsDashboardPresentation.columns(maxWidth.value, fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            values.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { value -> Box(Modifier.weight(1f).fillMaxHeight()) { content(value) } }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdaptivePair(content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (InsightsDashboardPresentation.columns(maxWidth.value, fontScale) == 1)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) { content() }
    }
}

@Composable
private fun DashboardSection(title: String, detail: String? = null, topPadding: Int = 24) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AdaptivePair {
            EditorialText(title, 25, 30, Modifier.semantics { heading() })
            detail?.let { Caption(it) }
        }
    }
}

@Composable
private fun DashboardPanel(modifier: Modifier = Modifier, hero: Boolean = false, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(if (hero) 20.dp else 18.dp),
        color = if (hero) heroColor() else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { content() }
}

@Composable
private fun heroColor(): Color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) InsightsHeroLight else InsightsHeroDark

@Composable
private fun statusColor(value: String?): Color = when (value?.lowercase(Locale.ROOT)) {
    "ok", "running", "connected" -> MaterialTheme.colorScheme.secondary
    "degraded", "error", "failed" -> MaterialTheme.colorScheme.error
    "warning", "starting" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun EditorialText(text: String, size: Int, lineHeight: Int, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, modifier, fontFamily = SettingsEditorial, fontWeight = FontWeight.Normal,
        fontSize = size.sp, lineHeight = lineHeight.sp, color = color)
}

@Composable
private fun Caption(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun Eyebrow(text: String, @DrawableRes icon: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun <T> LazyListScope.dashboardRows(values: List<T>, content: @Composable (T) -> Unit) {
    itemsIndexed(values) { index, value ->
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(
                topStart = if (index == 0) 18.dp else 0.dp, topEnd = if (index == 0) 18.dp else 0.dp,
                bottomStart = if (index == values.lastIndex) 18.dp else 0.dp, bottomEnd = if (index == values.lastIndex) 18.dp else 0.dp
            ), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) { content(value) }
        }
    }
}

@Composable
private fun ModelBreakdown(row: UsageRow, share: Float?, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AdaptivePair {
            Text(row.name, style = MaterialTheme.typography.titleSmall)
            Text(formatMetric(Metric("", row.count)), style = MaterialTheme.typography.bodyLarge)
        }
        share?.let { Meter(it, color) }
        row.cost?.let { Caption("${formatMetric(Metric("", it, "USD"))} estimated") }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(5.dp)) {
        drawRoundRect(color.copy(alpha = 0.15f), size = size, cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

@Composable
private fun InsightsNotice(message: String, retry: (() -> Unit)? = null, loading: Boolean = false) {
    DashboardPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (retry != null) TextButton(onClick = retry, enabled = !loading,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Retry") }
        }
    }
}

@Composable
private fun InsightsDailyChart(days: List<DailyUsage>) {
    val locale = LocalConfiguration.current.locales[0]
    val inputColor = MaterialTheme.colorScheme.secondary
    val outputColor = MaterialTheme.colorScheme.primary
    val chart = InsightsDashboardPresentation.dailyChart(days)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val fontScale = LocalDensity.current.fontScale
    val plotHeight = 160.dp * fontScale.coerceAtLeast(1f)
    val labelHeight = 24.dp * fontScale.coerceAtLeast(1f)
    val chartDescription = buildString {
        append("Daily input and output token chart. ")
        days.forEach { day ->
            append("${day.day}: input ${InsightsDashboardPresentation.formatTokens(day.input, locale)}, ")
            append("output ${InsightsDashboardPresentation.formatTokens(day.output, locale)}. ")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = chartDescription
        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.height(plotHeight + labelHeight).width(IntrinsicSize.Max),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                chart.ticks.asReversed().forEach { tick ->
                    Box(Modifier.height(labelHeight), contentAlignment = Alignment.CenterEnd) {
                        Caption(InsightsDashboardPresentation.formatTokens(tick, locale))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().padding(top = labelHeight / 2).height(plotHeight)) {
                    chart.ticks.indices.forEach { index ->
                        val y = size.height * (1f - index / 4f)
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                    val slot = size.width / days.size
                    chart.bars.forEachIndexed { index, bar ->
                        val input = (bar.input ?: 0f) * size.height
                        val output = (bar.output ?: 0f) * size.height
                        val width = InsightsDashboardPresentation.dailyBarWidth(slot, 3.dp.toPx())
                        if (bar.output != null) drawRect(outputColor, Offset(index * slot, size.height - output), Size(width, output))
                        if (bar.input != null) drawRect(inputColor, Offset(index * slot, size.height - output - input), Size(width, input))
                    }
                }
                // Date labels share the bars' plot width, excluding the numeric axis gutter.
                Row(Modifier.fillMaxWidth().padding(top = labelHeight / 2), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0, days.lastIndex / 2, days.lastIndex).distinct().forEach { index ->
                        Caption(runCatching { LocalDate.parse(days[index].day).format(DateTimeFormatter.ofPattern("MMM d", locale)) }
                            .getOrDefault(days[index].day))
                    }
                }
            }
        }
        AdaptivePair {
            Legend("Input tokens", inputColor)
            Legend("Output tokens", outputColor)
        }
        if (days.any { it.input == null || it.output == null }) Caption("Missing values are unavailable.")
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Caption(label)
    }
}
