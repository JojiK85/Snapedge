package com.snapedge.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.RemoteViews
import com.snapedge.app.R
import com.snapedge.app.data.PreferencesManager
import com.snapedge.app.service.EdgeOverlayService
import com.snapedge.app.ui.MainActivity

/**
 * Home-screen Quick Bar Widget for toggling the Edge Sidebar or launching Circle OCR & Long Shot directly.
 */
class SnapEdgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_EDGE) {
            if (!Settings.canDrawOverlays(context)) {
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appIntent)
                return
            }
            val prefs = PreferencesManager(context)
            val nextEnabled = !prefs.getEdgeConfig().enabled
            prefs.setEdgeEnabled(nextEnabled)
            if (nextEnabled) {
                EdgeOverlayService.start(context)
            } else {
                context.startService(
                    Intent(context, EdgeOverlayService::class.java).apply {
                        action = EdgeOverlayService.ACTION_STOP
                    }
                )
            }
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SnapEdgeWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }

    companion object {
        private const val ACTION_TOGGLE_EDGE = "com.snapedge.app.ACTION_TOGGLE_EDGE"

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = PreferencesManager(context)
            val enabled = prefs.getEdgeConfig().enabled && Settings.canDrawOverlays(context)

            val views = RemoteViews(context.packageName, R.layout.widget_snapedge)
            views.setTextViewText(R.id.widgetTvEdgeState, if (enabled) "Edge ON" else "Edge OFF")

            val toggleIntent = Intent(context, SnapEdgeWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_EDGE
            }
            views.setOnClickPendingIntent(
                R.id.widgetBtnToggleEdge,
                PendingIntent.getBroadcast(
                    context,
                    10,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val circleIntent = Intent(context, EdgeOverlayService::class.java).apply {
                action = EdgeOverlayService.ACTION_CIRCLE_OCR
            }
            views.setOnClickPendingIntent(
                R.id.widgetBtnCircleOcr,
                PendingIntent.getForegroundService(
                    context,
                    11,
                    circleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val longShotIntent = Intent(context, EdgeOverlayService::class.java).apply {
                action = EdgeOverlayService.ACTION_LONG_SHOT
            }
            views.setOnClickPendingIntent(
                R.id.widgetBtnLongShot,
                PendingIntent.getForegroundService(
                    context,
                    12,
                    longShotIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            manager.updateAppWidget(widgetId, views)
        }
    }
}
