package com.variableduck.build.habittracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * Adds the widget from inside the app. Some launchers (ColorOS/OxygenOS after a system update, for
 * one) leave the app out of their widget list even though Android still knows about the widget.
 */
object WidgetPinner {
    /** Asks the launcher to place the widget. Returns a message to show when it can't. */
    fun requestPin(context: Context): String? {
        val manager = AppWidgetManager.getInstance(context)
            ?: return "This device doesn't support home-screen widgets."
        val provider = ComponentName(context, HabitWidgetReceiver::class.java)
        return try {
            val registered = manager
                .getInstalledProvidersForPackage(context.packageName, Process.myUserHandle())
                .any { it.provider == provider }
            when {
                !registered ->
                    "Android isn't listing this app's widget. Restart the phone and try again; " +
                        "if it's still missing, reinstall the app (export a backup first)."
                !manager.isRequestPinAppWidgetSupported ->
                    "Your home screen doesn't let apps add widgets. Long-press the home screen, " +
                        "open Widgets and look for \"Habit Tracker\"."
                manager.requestPinAppWidget(provider, null, null) -> null
                else -> "The home screen refused to add the widget. Try again from the home screen."
            }
        } catch (e: RuntimeException) {
            // Launchers and OEM widget services are not always well behaved.
            Log.w("WidgetPinner", "Pin request failed", e)
            "Couldn't add the widget: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
