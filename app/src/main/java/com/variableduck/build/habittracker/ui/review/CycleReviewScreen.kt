package com.variableduck.build.habittracker.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.variableduck.build.habittracker.data.repo.CycleRepository
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.data.settings.SettingsRepository
import com.variableduck.build.habittracker.domain.CycleCalendar
import com.variableduck.build.habittracker.model.CycleOutcome
import com.variableduck.build.habittracker.model.CycleReview
import com.variableduck.build.habittracker.model.CycleReviewItem
import com.variableduck.build.habittracker.model.Habit
import com.variableduck.build.habittracker.model.LevelUpDecision
import com.variableduck.build.habittracker.ui.common.Formatter
import com.variableduck.build.habittracker.ui.common.ScreenHeader
import com.variableduck.build.habittracker.ui.common.rememberFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewItemUi(val item: CycleReviewItem, val habit: Habit?)

data class ReviewUi(val review: CycleReview, val cycleNumber: Long?, val items: List<ReviewItemUi>) {
    val hasPendingPrompts: Boolean get() = items.any { it.item.decision == LevelUpDecision.PENDING }
}

data class CycleReviewUiState(val loading: Boolean = true, val reviews: List<ReviewUi> = emptyList())

@HiltViewModel
class CycleReviewViewModel @Inject constructor(
    private val cycles: CycleRepository,
    tracker: TrackerRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<CycleReviewUiState> = combine(
        cycles.observePendingReviews(),
        tracker.observeAllHabits(),
        settings.settings,
    ) { reviews, habits, appSettings ->
        val byId = habits.associateBy { it.id }
        CycleReviewUiState(
            loading = false,
            reviews = reviews.map { r ->
                ReviewUi(
                    review = r.review,
                    cycleNumber = appSettings.cycleStart?.let { CycleCalendar.cycleFor(it, r.review.cycleStart).number },
                    items = r.items.map { ReviewItemUi(it, byId[it.habitId]) }
                        .sortedBy { it.habit?.slot ?: Int.MAX_VALUE },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleReviewUiState())

    fun decide(reviewId: Long, habitId: Long, accept: Boolean) {
        viewModelScope.launch { cycles.decideLevelUp(reviewId, habitId, accept) }
    }

    fun done(reviewId: Long) {
        viewModelScope.launch { cycles.acknowledge(reviewId) }
    }
}

@Composable
fun CycleReviewScreen(onClose: () -> Unit, viewModel: CycleReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatter = rememberFormatter()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Cycle review", "What happens to your targets after 14 days", onBack = onClose)
        if (!state.loading && state.reviews.isEmpty()) {
            Text("All caught up. There is nothing to review.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("Back to today") }
        }
        state.reviews.forEach { review ->
            ReviewCard(
                review = review,
                formatter = formatter,
                onDecide = { habitId, accept -> viewModel.decide(review.review.id, habitId, accept) },
                onDone = { viewModel.done(review.review.id) },
            )
        }
    }
}

@Composable
private fun ReviewCard(
    review: ReviewUi,
    formatter: Formatter,
    onDecide: (Long, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                (review.cycleNumber?.let { "Cycle $it · " } ?: "") +
                    "${formatter.date(review.review.cycleStart)} – ${formatter.date(review.review.cycleEnd)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            review.items.forEach { ui ->
                ReviewItemRow(ui, formatter, onDecide)
                HorizontalDivider()
            }
            Button(
                onClick = onDone,
                enabled = !review.hasPendingPrompts,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text(if (review.hasPendingPrompts) "Answer the level-up questions first" else "Done") }
        }
    }
}

@Composable
private fun ReviewItemRow(ui: ReviewItemUi, formatter: Formatter, onDecide: (Long, Boolean) -> Unit) {
    val item = ui.item
    val habit = ui.habit
    val name = habit?.name ?: "Removed habit"
    val type = habit?.type
    val change = if (type != null) formatter.change(type, item.oldTarget, item.newTarget) else ""
    val current = if (type != null) formatter.target(type, item.oldTarget) else ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$name · ${item.yesCount}/14 YES", style = MaterialTheme.typography.titleSmall)
        when (item.outcome) {
            CycleOutcome.LEVEL_UP_PROMPT -> when (item.decision) {
                LevelUpDecision.PENDING -> {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Level up? $change", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { onDecide(item.habitId, false) },
                                    modifier = Modifier.height(48.dp),
                                ) { Text("Not yet") }
                                Button(
                                    onClick = { onDecide(item.habitId, true) },
                                    modifier = Modifier.height(48.dp),
                                ) { Text("Yes") }
                            }
                        }
                    }
                }
                LevelUpDecision.ACCEPTED -> Text("Levelled up: $change")
                LevelUpDecision.DECLINED -> Text("Not yet. Staying at $current.")
                LevelUpDecision.SUPERSEDED, null -> Text("No change.")
            }
            CycleOutcome.STEPPED_DOWN -> Text(
                "Fewer than the step-down threshold, so the target was stepped down automatically: $change",
                color = MaterialTheme.colorScheme.tertiary,
            )
            CycleOutcome.AT_STARTING_TARGET -> Text("Already at the starting target ($current), so it stays there.")
            CycleOutcome.NO_CHANGE -> Text("No change. Staying at $current.")
        }
    }
}
