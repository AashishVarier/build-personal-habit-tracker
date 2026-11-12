@file:OptIn(ExperimentalMaterial3Api::class)

package com.variableduck.build.habittracker.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.variableduck.build.habittracker.data.repo.TrackerRepository
import com.variableduck.build.habittracker.domain.RewardRule
import com.variableduck.build.habittracker.domain.RewardStatus
import com.variableduck.build.habittracker.model.HabitType
import com.variableduck.build.habittracker.ui.common.Formatter
import com.variableduck.build.habittracker.ui.common.TimePickerDialog
import com.variableduck.build.habittracker.ui.common.rememberFormatter
import com.variableduck.build.habittracker.ui.common.rememberNotificationAccess
import com.variableduck.build.habittracker.ui.theme.LocalStatusColors

@Composable
fun TodayScreen(onOpenReview: () -> Unit, viewModel: TodayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationAccess = rememberNotificationAccess()
    TodayContent(
        state = state,
        notificationsAllowed = notificationAccess.allowed,
        onEnableNotifications = notificationAccess.request,
        onSelect = viewModel::select,
        onToggle = viewModel::toggle,
        onActualTime = viewModel::setActualTime,
        onRemarkChange = viewModel::onRemarkChange,
        onOpenReview = onOpenReview,
    )
}

@Composable
fun TodayContent(
    state: TodayUiState,
    notificationsAllowed: Boolean,
    onEnableNotifications: () -> Unit,
    onSelect: (DaySelection) -> Unit,
    onToggle: (Long) -> Unit,
    onActualTime: (Long, Int?) -> Unit,
    onRemarkChange: (String) -> Unit,
    onOpenReview: () -> Unit,
) {
    val formatter = rememberFormatter()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading) return@Column
        Column {
            Text(
                formatter.dayHeader(state.date),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.cycleNumber != null && state.dayInCycle != null) {
                Text(
                    "Cycle ${state.cycleNumber} · day ${state.dayInCycle} of 14",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DaySelection.entries.forEachIndexed { index, day ->
                SegmentedButton(
                    selected = state.selection == day,
                    onClick = { onSelect(day) },
                    shape = SegmentedButtonDefaults.itemShape(index, DaySelection.entries.size),
                ) { Text(if (day == DaySelection.TODAY) "Today" else "Yesterday") }
            }
        }

        if (!notificationsAllowed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Notifications are off, so the daily reminder can't appear.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = onEnableNotifications) { Text("Turn on") }
                }
            }
        }

        if (state.pendingReview) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Your cycle review is ready.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onOpenReview) { Text("Review") }
                }
            }
        }

        if (state.tiles.isEmpty()) {
            Text(
                if (state.selection == DaySelection.TODAY) {
                    "No habits yet. Add one on the Habits tab."
                } else {
                    "Nothing was tracked on this day."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            state.tiles.chunked(2).forEach { pair ->
                // Equal-height tiles in each row, whatever their content.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    pair.forEach { tile ->
                        HabitTile(
                            tile = tile,
                            formatter = formatter,
                            onToggle = { onToggle(tile.entry.habit.id) },
                            onActualTime = { onActualTime(tile.entry.habit.id, it) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        RewardCard(state.reward)

        OutlinedTextField(
            value = state.remark,
            onValueChange = onRemarkChange,
            label = { Text("Remark for the day (optional)") },
            singleLine = true,
            supportingText = {
                Text(
                    "${state.remark.length}/${TrackerRepository.REMARK_MAX_LENGTH}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("remark-field"),
        )

        if (state.goal.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Overall goal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(state.goal, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun HabitTile(
    tile: TileUi,
    formatter: Formatter,
    onToggle: () -> Unit,
    onActualTime: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val habit = tile.entry.habit
    val done = tile.entry.done
    val status = LocalStatusColors.current
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(if (done) status.yes else status.no, label = "tile")
    val content = if (done) status.onYes else status.onNo
    var pickTime by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .heightIn(min = 150.dp)
            .testTag("habit-tile-${habit.id}")
            .semantics {
                stateDescription = if (done) "YES" else "NO"
                contentDescription = habit.name
            }
            .toggleable(value = done, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onToggle()
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // Always two lines tall, so the lines below start at the same height in every tile.
                Text(
                    habit.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (habit.mandatory) {
                    Icon(Icons.Filled.Star, contentDescription = "Mandatory", modifier = Modifier.padding(start = 4.dp))
                }
            }
            Text(
                formatter.target(habit.type, habit.currentTarget),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (done) "YES" else "NO",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (habit.type == HabitType.DEADLINE) {
                val actual = tile.entry.actualTime
                val label = when {
                    actual == null -> "Set actual time"
                    habit.currentTarget != null ->
                        "${formatter.clock(actual)} · ${formatter.deadlineDelta(habit.currentTarget, actual)}"
                    else -> formatter.clock(actual)
                }
                TextButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                "Graduation ${tile.graduation.label}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }

    if (pickTime) {
        TimePickerDialog(
            title = "Actual time for ${habit.name}",
            initialMinuteOfDay = tile.entry.actualTime ?: habit.currentTarget ?: (7 * 60),
            onDismiss = { pickTime = false },
            onConfirm = {
                onActualTime(it)
                pickTime = false
            },
            onClear = if (tile.entry.actualTime != null) {
                {
                    onActualTime(null)
                    pickTime = false
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun RewardCard(reward: RewardStatus) {
    val status = LocalStatusColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reward-card"),
        colors = CardDefaults.cardColors(
            containerColor = if (reward.unlocked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (reward.unlocked) Icons.Filled.Star else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (reward.unlocked) status.reward else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                if (reward.unlocked) {
                    Text(
                        "Guilt-free reward unlocked",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Enjoy the reward you picked for today.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Reward locked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    RewardRule.missingMessages(reward).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (reward.habitCount > 0) {
                        Text(
                            "${reward.yesCount} of ${reward.requiredCount} needed so far.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
