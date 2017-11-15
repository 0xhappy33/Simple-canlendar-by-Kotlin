package com.simplemobiletools.calendar.helpers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Reminders
import android.util.SparseIntArray
import com.simplemobiletools.calendar.activities.SimpleActivity
import com.simplemobiletools.calendar.extensions.config
import com.simplemobiletools.calendar.extensions.dbHelper
import com.simplemobiletools.calendar.extensions.scheduleCalDAVSync
import com.simplemobiletools.calendar.models.CalDAVCalendar
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.calendar.models.EventType
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.PERMISSION_READ_CALENDAR
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_CALENDAR
import java.util.*
import kotlin.collections.ArrayList

class CalDAVHandler(val context: Context) {
    fun refreshCalendars(activity: SimpleActivity? = null, callback: () -> Unit) {
        val dbHelper = context.dbHelper
        for (calendar in getCalDAVCalendars(activity, context.config.caldavSyncedCalendarIDs)) {
            val localEventType = dbHelper.getEventTypeWithCalDAVCalendarId(calendar.id) ?: continue
            localEventType.apply {
                title = calendar.displayName
                caldavDisplayName = calendar.displayName
                caldavEmail = calendar.accountName
                color = calendar.color
                dbHelper.updateLocalEventType(this)
            }

            CalDAVHandler(context).fetchCalDAVCalendarEvents(calendar.id, localEventType.id, activity)
        }
        context.scheduleCalDAVSync(true)
        callback()
    }

    fun getCalDAVCalendars(activity: SimpleActivity? = null, ids: String = ""): List<CalDAVCalendar> {
        val calendars = ArrayList<CalDAVCalendar>()
        if (!context.hasPermission(PERMISSION_WRITE_CALENDAR) || !context.hasPermission(PERMISSION_READ_CALENDAR)) {
            return calendars
        }

        val uri = CalendarContract.Calendars.CONTENT_URI
        val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)

        val selection = if (ids.trim().isNotEmpty()) "${CalendarContract.Calendars._ID} IN ($ids)" else null
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val id = cursor.getIntValue(CalendarContract.Calendars._ID)
                    val displayName = cursor.getStringValue(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                    val accountName = cursor.getStringValue(CalendarContract.Calendars.ACCOUNT_NAME)
                    val ownerName = cursor.getStringValue(CalendarContract.Calendars.OWNER_ACCOUNT)
                    val color = cursor.getIntValue(CalendarContract.Calendars.CALENDAR_COLOR)
                    val accessLevel = cursor.getIntValue(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                    val calendar = CalDAVCalendar(id, displayName, accountName, ownerName, color, accessLevel)
                    calendars.add(calendar)
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            activity?.showErrorToast(e)
        } finally {
            cursor?.close()
        }
        return calendars
    }

    fun updateCalDAVCalendar(eventType: EventType): Boolean {
        val uri = CalendarContract.Calendars.CONTENT_URI
        val values = fillCalendarContentValues(eventType)
        val newUri = ContentUris.withAppendedId(uri, eventType.caldavCalendarId.toLong())
        return try {
            context.contentResolver.update(newUri, values, null, null) == 1
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun fillCalendarContentValues(eventType: EventType): ContentValues {
        val colorKey = getEventTypeColorKey(eventType)
        return ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_COLOR_KEY, colorKey)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, eventType.title)
        }
    }

    private fun getEventTypeColorKey(eventType: EventType): Int {
        val uri = CalendarContract.Colors.CONTENT_URI
        val projection = arrayOf(CalendarContract.Colors.COLOR_KEY)
        val selection = "${CalendarContract.Colors.COLOR_TYPE} = ? AND ${CalendarContract.Colors.COLOR} = ? AND ${CalendarContract.Colors.ACCOUNT_NAME} = ?"
        val selectionArgs = arrayOf(CalendarContract.Colors.TYPE_CALENDAR.toString(), eventType.color.toString(), eventType.caldavEmail)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                return cursor.getStringValue(CalendarContract.Colors.COLOR_KEY).toInt()
            }
        } finally {
            cursor?.close()
        }

        return -1
    }

    // it doesnt work properly, needs better SyncAdapter handling
    private fun insertNewColor(eventType: EventType): Int {
        val maxId = getMaxColorId(eventType) + 1

        val values = ContentValues().apply {
            put(CalendarContract.Colors.COLOR_KEY, maxId)
            put(CalendarContract.Colors.COLOR, eventType.color)
            put(CalendarContract.Colors.ACCOUNT_NAME, eventType.caldavEmail)
            put(CalendarContract.Colors.ACCOUNT_TYPE, "com.google")
            put(CalendarContract.Colors.COLOR_TYPE, CalendarContract.Colors.TYPE_CALENDAR)
        }

        val uri = CalendarContract.Colors.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, eventType.caldavEmail)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
                .build()

        return if (context.contentResolver.insert(uri, values) != null) {
            maxId
        } else {
            0
        }
    }

    private fun getMaxColorId(eventType: EventType): Int {
        val uri = CalendarContract.Colors.CONTENT_URI
        val projection = arrayOf(CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR)
        val selection = "${CalendarContract.Colors.COLOR_TYPE} = ? AND ${CalendarContract.Colors.ACCOUNT_NAME} = ?"
        val selectionArgs = arrayOf(CalendarContract.Colors.TYPE_CALENDAR.toString(), eventType.caldavEmail)
        var maxId = 1

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    maxId = Math.max(maxId, cursor.getIntValue(CalendarContract.Colors.COLOR_KEY))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        return maxId
    }

    fun getAvailableCalDAVCalendarColors(eventType: EventType): ArrayList<Int> {
        val colors = SparseIntArray()
        val uri = CalendarContract.Colors.CONTENT_URI
        val projection = arrayOf(CalendarContract.Colors.COLOR, CalendarContract.Colors.COLOR_KEY)
        val selection = "${CalendarContract.Colors.COLOR_TYPE} = ? AND ${CalendarContract.Colors.ACCOUNT_NAME} = ?"
        val selectionArgs = arrayOf(CalendarContract.Colors.TYPE_CALENDAR.toString(), eventType.caldavEmail)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val colorKey = cursor.getIntValue(CalendarContract.Colors.COLOR_KEY)
                    val color = cursor.getIntValue(CalendarContract.Colors.COLOR)
                    colors.put(colorKey, color)
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        val sortedColors = ArrayList<Int>(colors.size())
        (0 until colors.size()).mapTo(sortedColors) { colors[it] }

        return sortedColors
    }

    private fun fetchCalDAVCalendarEvents(calendarId: Int, eventTypeId: Int, activity: SimpleActivity?) {
        val importIdsMap = HashMap<String, Event>()
        val fetchedEventIds = ArrayList<String>()
        val existingEvents = context.dbHelper.getEventsFromCalDAVCalendar(calendarId)
        existingEvents.forEach {
            importIdsMap.put(it.importId, it)
        }

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.EVENT_LOCATION)

        val selection = "${CalendarContract.Events.CALENDAR_ID} = $calendarId"
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val id = cursor.getLongValue(CalendarContract.Events._ID)
                    val title = cursor.getStringValue(CalendarContract.Events.TITLE) ?: continue
                    val description = cursor.getStringValue(CalendarContract.Events.DESCRIPTION) ?: ""
                    val startTS = (cursor.getLongValue(CalendarContract.Events.DTSTART) / 1000).toInt()
                    var endTS = (cursor.getLongValue(CalendarContract.Events.DTEND) / 1000).toInt()
                    val allDay = cursor.getIntValue(CalendarContract.Events.ALL_DAY)
                    val rrule = cursor.getStringValue(CalendarContract.Events.RRULE) ?: ""
                    val location = cursor.getStringValue(CalendarContract.Events.EVENT_LOCATION) ?: ""
                    val reminders = getCalDAVEventReminders(id)

                    if (endTS == 0) {
                        val duration = cursor.getStringValue(CalendarContract.Events.DURATION) ?: ""
                        endTS = startTS + Parser().parseDurationSeconds(duration)
                    }

                    val importId = getCalDAVEventImportId(calendarId, id)
                    val repeatRule = Parser().parseRepeatInterval(rrule, startTS)
                    val event = Event(0, startTS, endTS, title, description, reminders.getOrElse(0, { -1 }),
                            reminders.getOrElse(1, { -1 }), reminders.getOrElse(2, { -1 }), repeatRule.repeatInterval,
                            importId, allDay, repeatRule.repeatLimit, repeatRule.repeatRule, eventTypeId, source = "$CALDAV-$calendarId",
                            location = location)

                    if (event.getIsAllDay() && endTS > startTS) {
                        event.endTS -= DAY
                    }

                    fetchedEventIds.add(importId)
                    if (importIdsMap.containsKey(event.importId)) {
                        val existingEvent = importIdsMap[importId]
                        val originalEventId = existingEvent!!.id
                        existingEvent.id = 0
                        if (existingEvent.hashCode() != event.hashCode()) {
                            event.id = originalEventId
                            context.dbHelper.update(event, false) {
                            }
                        }
                    } else {
                        context.dbHelper.insert(event, false) {
                            importIdsMap.put(event.importId, event)
                        }
                    }
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            activity?.showErrorToast(e)
        } finally {
            cursor?.close()
        }

        val eventIdsToDelete = ArrayList<String>()
        importIdsMap.keys.filter { !fetchedEventIds.contains(it) }.forEach {
            val caldavEventId = it
            existingEvents.forEach {
                if (it.importId == caldavEventId) {
                    eventIdsToDelete.add(it.id.toString())
                }
            }
        }

        eventIdsToDelete.forEach {
            context.dbHelper.deleteEvents(eventIdsToDelete.toTypedArray(), false)
        }
    }

    fun insertCalDAVEvent(event: Event) {
        val uri = CalendarContract.Events.CONTENT_URI
        val values = fillEventContentValues(event)
        val newUri = context.contentResolver.insert(uri, values)

        val calendarId = event.getCalDAVCalendarId()
        val eventRemoteID = java.lang.Long.parseLong(newUri.lastPathSegment)
        event.importId = getCalDAVEventImportId(calendarId, eventRemoteID)

        setupCalDAVEventReminders(event)
        setupCalDAVEventImportId(event)
    }

    fun updateCalDAVEvent(event: Event) {
        val uri = CalendarContract.Events.CONTENT_URI
        val values = fillEventContentValues(event)
        val eventRemoteID = event.getCalDAVEventId()
        event.importId = getCalDAVEventImportId(event.getCalDAVCalendarId(), eventRemoteID)

        val newUri = ContentUris.withAppendedId(uri, eventRemoteID)
        context.contentResolver.update(newUri, values, null, null)

        setupCalDAVEventReminders(event)
        setupCalDAVEventImportId(event)
    }

    private fun setupCalDAVEventReminders(event: Event) {
        clearEventReminders(event)
        event.getReminders().forEach {
            ContentValues().apply {
                put(Reminders.MINUTES, it)
                put(Reminders.EVENT_ID, event.getCalDAVEventId())
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
                context.contentResolver.insert(Reminders.CONTENT_URI, this)
            }
        }
    }

    private fun setupCalDAVEventImportId(event: Event) {
        context.dbHelper.updateEventImportIdAndSource(event.id, event.importId, "$CALDAV-${event.getCalDAVCalendarId()}")
    }

    private fun fillEventContentValues(event: Event): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, event.getCalDAVCalendarId())
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.DTSTART, event.startTS * 1000L)
            put(CalendarContract.Events.ALL_DAY, if (event.getIsAllDay()) 1 else 0)
            put(CalendarContract.Events.RRULE, Parser().getRepeatCode(event))
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().toString())
            put(CalendarContract.Events.EVENT_LOCATION, event.location)

            if (event.getIsAllDay() && event.endTS > event.startTS)
                event.endTS += DAY

            if (event.repeatInterval > 0) {
                put(CalendarContract.Events.DURATION, getDurationCode(event))
                putNull(CalendarContract.Events.DTEND)
            } else {
                put(CalendarContract.Events.DTEND, event.endTS * 1000L)
                putNull(CalendarContract.Events.DURATION)
            }
        }
    }

    private fun clearEventReminders(event: Event) {
        val selection = "${Reminders.EVENT_ID} = ?"
        val selectionArgs = arrayOf(event.getCalDAVEventId().toString())
        context.contentResolver.delete(Reminders.CONTENT_URI, selection, selectionArgs)
    }

    private fun getDurationCode(event: Event): String {
        return if (event.getIsAllDay()) {
            val dur = Math.max(1, (event.endTS - event.startTS) / DAY)
            "P${dur}D"
        } else {
            Parser().getDurationCode((event.endTS - event.startTS) / 60)
        }
    }

    fun deleteCalDAVCalendarEvents(calendarId: Long) {
        val events = context.dbHelper.getCalDAVCalendarEvents(calendarId)
        val eventIds = events.map { it.id.toString() }.toTypedArray()
        context.dbHelper.deleteEvents(eventIds, false)
    }

    fun deleteCalDAVEvent(event: Event) {
        val uri = CalendarContract.Events.CONTENT_URI
        val contentUri = ContentUris.withAppendedId(uri, event.getCalDAVEventId())
        try {
            context.contentResolver.delete(contentUri, null, null)
        } catch (ignored: Exception) {

        }
    }

    private fun getCalDAVEventReminders(eventId: Long): List<Int> {
        val reminders = ArrayList<Int>()
        val uri = CalendarContract.Reminders.CONTENT_URI
        val projection = arrayOf(
                CalendarContract.Reminders.MINUTES,
                CalendarContract.Reminders.METHOD)
        val selection = "${CalendarContract.Reminders.EVENT_ID} = $eventId"
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val minutes = cursor.getIntValue(CalendarContract.Reminders.MINUTES)
                    val method = cursor.getIntValue(CalendarContract.Reminders.METHOD)
                    if (method == CalendarContract.Reminders.METHOD_ALERT) {
                        reminders.add(minutes)
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }
        return reminders
    }

    private fun getCalDAVEventImportId(calendarId: Int, eventId: Long) = "$CALDAV-$calendarId-$eventId"
}
