package com.variableduck.build.habittracker.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.rememberFormatter
import com.variableduck.build.habittracker.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val anchor = state.anchor
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScreenHeader("History", "Swipe between 14-day cycles")
        if (state.loading || anchor == null) return@Column
        val pagerState = rememberPagerState(initialPage = state.pageCount - 1) { state.pageCount }
        val scope = rememberCoroutineScope()
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            val index = state.indexForPage(page)
            val flow = remember(anchor, index) { viewModel.cyclePage(anchor, index) }
            val cyclePage by flow.collectAsStateWithLifecycle(initialValue = null)
            cyclePage?.let { data ->
                CyclePageView(
                    page = data,
                    canGoBack = page > 0,
                    canGoForward = page < state.pageCount - 1,
                    onBack = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                    onForward = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                )
            }
        }
    }
}

@Composable
private fun CyclePageView(
    page: CyclePage,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val formatter = rememberFormatter()
    val status = LocalStatusColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = canGoBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous cycle")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (page.cycle.index >= 0) "Cycle ${page.cycle.number}" else "Before the first cycle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatter.date(page.cycle.start)} – ${formatter.date(page.cycle.end)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next cycle")
            }
        }

        // Header row: habit names.
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Date", modifier = Modifier.width(DATE_WIDTH), style = MaterialTheme.typography.labelMedium)
            page.habits.forEach { habit ->
                Text(
                    habit.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(REWARD_WIDTH))
        }
        HorizontalDivider()

        page.rows.forEach { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatter.shortDay(row.date),
                        modifier = Modifier.width(DATE_WIDTH),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.future) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    row.cells.forEachIndexed { i, cell ->
                        val habitName = page.habits[i].name
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val (color, label) = when (cell) {
                                CellState.YES -> status.yes to "YES"
                                CellState.NO -> status.no to "NO"
                                CellState.NONE -> status.missing to "no entry"
                            }
                            Box(
                                Modifier
                                    .size(width = 40.dp, height = 28.dp)
                                    .background(color, RoundedCornerShape(6.dp))
                                    .semantics { contentDescription = "$habitName ${formatter.shortDay(row.date)}: $label" },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cell != CellState.NONE) {
                                    Text(
                                        if (cell == CellState.YES) "✓" else "✗",
                                        color = if (cell == CellState.YES) status.onYes else status.onNo,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.width(REWARD_WIDTH), contentAlignment = Alignment.Center) {
                        if (row.reward) {
                            Icon(Icons.Filled.Star, contentDescription = "Reward unlocked", tint = status.reward)
                        }
                    }
                }
                if (row.remark.isNotBlank()) {
                    Text(
                        row.remark,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = DATE_WIDTH, top = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Legend()
    }
}

@Composable
private fun Legend() {
    val status = LocalStatusColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(status.yes to "YES", status.no to "NO", status.missing to "No entry").forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = status.reward, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Reward", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val DATE_WIDTH = 84.dp
private val REWARD_WIDTH = 32.dp
