package com.simplemobiletools.calendar.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.activities.DayActivity
import com.simplemobiletools.calendar.activities.SplashActivity
import com.simplemobiletools.calendar.extensions.config
import com.simplemobiletools.calendar.extensions.launchNewEventIntent
import com.simplemobiletools.calendar.services.WidgetService
import com.simplemobiletools.commons.extensions.getColoredBitmap
import com.simplemobiletools.commons.extensions.setBackgroundColor
import com.simplemobiletools.commons.extensions.setText
import com.simplemobiletools.commons.extensions.setTextSize
import org.joda.time.DateTime

class MyWidgetListProvider : AppWidgetProvider() {
    private val NEW_EVENT = "new_event"
    private val LAUNCH_TODAY = "launch_today"

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        performUpdate(context)
    }

    private fun performUpdate(context: Context) {
        val fontSize = context.config.getFontSize()
        val textColor = context.config.widgetTextColor

        val appWidgetManager = AppWidgetManager.getInstance(context)
        appWidgetManager.getAppWidgetIds(getComponentName(context)).forEach {
            val views = RemoteViews(context.packageName, R.layout.widget_event_list).apply {
                setBackgroundColor(R.id.widget_event_list_holder, context.config.widgetBgColor)
                setTextColor(R.id.widget_event_list_empty, textColor)
                setTextSize(R.id.widget_event_list_empty, fontSize)

                setTextColor(R.id.widget_event_list_today, textColor)
                setTextSize(R.id.widget_event_list_today, fontSize + 3)
            }

            val now = (System.currentTimeMillis() / 1000).toInt()
            val todayCode = Formatter.getDayCodeFromTS(now)
            val todayText = Formatter.getDayTitle(context, todayCode)
            views.setText(R.id.widget_event_list_today, todayText)

            views.setImageViewBitmap(R.id.widget_event_new_event, context.resources.getColoredBitmap(R.drawable.ic_plus, textColor))
            setupIntent(context, views, NEW_EVENT, R.id.widget_event_new_event)
            setupIntent(context, views, LAUNCH_TODAY, R.id.widget_event_list_today)

            Intent(context, WidgetService::class.java).apply {
                data = Uri.parse(this.toUri(Intent.URI_INTENT_SCHEME))
                views.setRemoteAdapter(R.id.widget_event_list, this)
            }

            val startActivityIntent = Intent(context, SplashActivity::class.java)
            val startActivityPendingIntent = PendingIntent.getActivity(context, 0, startActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            views.setPendingIntentTemplate(R.id.widget_event_list, startActivityPendingIntent)
            views.setEmptyView(R.id.widget_event_list, R.id.widget_event_list_empty)

            appWidgetManager.updateAppWidget(it, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(it, R.id.widget_event_list)
        }
    }

    private fun getComponentName(context: Context) = ComponentName(context, MyWidgetListProvider::class.java)

    private fun setupIntent(context: Context, views: RemoteViews, action: String, id: Int) {
        Intent(context, MyWidgetListProvider::class.java).apply {
            this.action = action
            val pendingIntent = PendingIntent.getBroadcast(context, 0, this, 0)
            views.setOnClickPendingIntent(id, pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NEW_EVENT -> context.launchNewEventIntent()
            LAUNCH_TODAY -> launchDayActivity(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun launchDayActivity(context: Context) {
        Intent(context, DayActivity::class.java).apply {
            putExtra(DAY_CODE, Formatter.getDayCodeFromDateTime(DateTime()))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }
}
