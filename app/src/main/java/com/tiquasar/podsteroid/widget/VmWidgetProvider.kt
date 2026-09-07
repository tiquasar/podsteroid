/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Home-screen widget that toggles the default VM. State is mirrored in a
 * private SharedPreferences so the widget reflects the last toggle without
 * needing the app process alive (the service is the source of truth at
 * runtime; this is a best-effort display).
 */
package com.tiquasar.podsteroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.VmRepository
import com.tiquasar.podsteroid.service.PodsteroidService

class VmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val wasRunning = isRunning(context)
            if (wasRunning) {
                PodsteroidService.stop(context, VmRepository.DEFAULT_VM_ID)
            } else {
                PodsteroidService.start(context, VmRepository.DEFAULT_VM_ID)
            }
            setRunning(context, !wasRunning)
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, VmWidgetProvider::class.java))
            for (id in ids) updateWidget(context, mgr, id)
        }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val running = isRunning(context)
        val views = RemoteViews(context.packageName, R.layout.widget_vm)
        views.setImageViewResource(R.id.widget_toggle, R.drawable.ic_tile_power)
        views.setTextViewText(
            R.id.widget_label,
            if (running) context.getString(R.string.widget_running)
            else context.getString(R.string.widget_stopped),
        )
        val toggle = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, VmWidgetProvider::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
        mgr.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_TOGGLE = "com.tiquasar.podsteroid.widget.ACTION_TOGGLE"
        private const val PREFS = "podsteroid_widget"
        private const val KEY_RUNNING = "default_running"

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)

        fun setRunning(context: Context, v: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RUNNING, v).apply()
    }
}
