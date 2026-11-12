package com.variableduck.build.habittracker.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.variableduck.build.habittracker.MainActivity
import com.variableduck.build.habittracker.data.repo.DataChangeNotifier
import com.variableduck.build.habittracker.data.repo.DayView
import com.variableduck.build.habittracker.data.repo.HabitEntry
import com.variableduck.build.habittracker.di.ApplicationScope
import com.variableduck.build.habittracker.di.ReceiverEntryPoint
import com.variableduck.build.habittracker.domain.RewardRule
import com.variableduck.build.habittracker.reminders.ReminderNotifier
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Home-screen widget: today's habits with one-tap YES, and the reward status. */
class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val tracker = deps.tracker()
        val today = deps.timeProvider().today()
        tracker.fillMissingDays(today)
        val initial = tracker.dayView(today)
        provideContent {
            val flow = remember(today) { tracker.observeDay(today) }
            val day by flow.collectAsState(initial)
            GlanceTheme { WidgetContent(day) }
        }
    }
}

@Composable
private fun WidgetContent(day: DayView) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp),
    ) {
        Text(
            text = "Today",
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (day.entries.isEmpty()) {
            Text(
                text = "Open the app to set up your habits.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            day.entries.forEach { entry ->
                HabitRow(entry)
                Spacer(GlanceModifier.height(4.dp))
            }
            Spacer(GlanceModifier.height(2.dp))
            val reward = day.reward
            val text = if (reward.unlocked) {
                "Guilt-free reward unlocked"
            } else {
                RewardRule.missingMessages(reward).firstOrNull() ?: "Reward locked"
            }
            Text(
                text = text,
                maxLines = 2,
                style = TextStyle(
                    fontWeight = if (reward.unlocked) FontWeight.Bold else FontWeight.Normal,
                    color = if (reward.unlocked) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                ),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        }
    }
}

@Composable
private fun HabitRow(entry: HabitEntry) {
    val done = entry.done
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(if (done) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surfaceVariant)
            .clickable(
                actionRunCallback<ToggleHabitAction>(
                    actionParametersOf(ToggleHabitAction.HabitIdKey to entry.habit.id),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (done) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant
        Text(text = if (done) "✓" else "○", style = TextStyle(fontWeight = FontWeight.Bold, color = color))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = entry.habit.name,
            maxLines = 1,
            style = TextStyle(color = color),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(text = if (done) "YES" else "NO", style = TextStyle(fontWeight = FontWeight.Bold, color = color))
    }
}

/** One tap flips today's entry (NO -> YES, or back if tapped by mistake). */
class ToggleHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[HabitIdKey] ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        deps.tracker().toggle(deps.timeProvider().today(), habitId)
        // Redraw this widget right away rather than waiting for the app-wide refresh.
        HabitWidget().update(context, glanceId)
    }

    companion object {
        val HabitIdKey = ActionParameters.Key<Long>("habit_id")
    }
}

class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}

/**
 * Keeps the widget and the reminder notification in sync with every change. Changes are
 * coalesced so a burst of writes causes a single refresh.
 */
@Singleton
class AppChangeNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val reminderNotifier: Provider<ReminderNotifier>,
) : DataChangeNotifier {
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                signals.receive()
                try {
                    HabitWidget().updateAll(context)
                    reminderNotifier.get().refreshIfShowing()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AppChangeNotifier", "Refresh failed", e)
                }
            }
        }
    }

    override fun notifyChanged() {
        signals.trySend(Unit)
    }
}
