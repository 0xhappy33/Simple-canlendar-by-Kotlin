package com.simplemobiletools.calendar.helpers

import android.content.Context
import com.simplemobiletools.calendar.extensions.config
import com.simplemobiletools.calendar.extensions.dbHelper
import com.simplemobiletools.calendar.extensions.getFilteredEvents
import com.simplemobiletools.calendar.extensions.seconds
import com.simplemobiletools.calendar.interfaces.MonthlyCalendar
import com.simplemobiletools.calendar.models.DayMonthly
import com.simplemobiletools.calendar.models.Event
import org.joda.time.DateTime
import java.util.*
import kotlin.collections.ArrayList

class MonthlyCalendarImpl(val mCallback: MonthlyCalendar, val mContext: Context) {
    private val DAYS_CNT = 42
    private val YEAR_PATTERN = "YYYY"

    private val mToday: String = DateTime().toString(Formatter.DAYCODE_PATTERN)
    private var mEvents = ArrayList<Event>()
    private var mFilterEventTypes = true

    lateinit var mTargetDate: DateTime

    fun updateMonthlyCalendar(targetDate: DateTime, filterEventTypes: Boolean = true) {
        mFilterEventTypes = filterEventTypes
        mTargetDate = targetDate
        val startTS = mTargetDate.minusMonths(1).seconds()
        val endTS = mTargetDate.plusMonths(1).seconds()
        mContext.dbHelper.getEvents(startTS, endTS) {
            gotEvents(it as ArrayList<Event>)
        }
    }

    fun getMonth(targetDate: DateTime) {
        updateMonthlyCalendar(targetDate)
    }

    fun getDays(markDaysWithEvents: Boolean) {
        val days = ArrayList<DayMonthly>(DAYS_CNT)
        val currMonthDays = mTargetDate.dayOfMonth().maximumValue
        var firstDayIndex = mTargetDate.withDayOfMonth(1).dayOfWeek
        if (!mContext.config.isSundayFirst)
            firstDayIndex -= 1
        val prevMonthDays = mTargetDate.minusMonths(1).dayOfMonth().maximumValue

        var isThisMonth = false
        var isToday: Boolean
        var value = prevMonthDays - firstDayIndex + 1
        var curDay: DateTime = mTargetDate

        for (i in 0 until DAYS_CNT) {
            when {
                i < firstDayIndex -> {
                    isThisMonth = false
                    curDay = mTargetDate.withDayOfMonth(1).minusMonths(1)
                }
                i == firstDayIndex -> {
                    value = 1
                    isThisMonth = true
                    curDay = mTargetDate
                }
                value == currMonthDays + 1 -> {
                    value = 1
                    isThisMonth = false
                    curDay = mTargetDate.withDayOfMonth(1).plusMonths(1)
                }
            }

            isToday = isThisMonth && isToday(mTargetDate, value)

            val newDay = curDay.withDayOfMonth(value)
            val dayCode = Formatter.getDayCodeFromDateTime(newDay)
            val day = DayMonthly(value, isThisMonth, isToday, dayCode, newDay.weekOfWeekyear, ArrayList())
            days.add(day)
            value++
        }

        if (markDaysWithEvents) {
            markDaysWithEvents(days)
        } else {
            mCallback.updateMonthlyCalendar(mContext, monthName, days, false)
        }
    }

    // it works more often than not, dont touch
    private fun markDaysWithEvents(days: ArrayList<DayMonthly>) {
        mContext.dbHelper.getEventTypes {
            val dayEvents = HashMap<String, ArrayList<Event>>()
            mEvents.forEach {
                val startDateTime = Formatter.getDateTimeFromTS(it.startTS)
                val endDateTime = Formatter.getDateTimeFromTS(it.endTS)
                val endCode = Formatter.getDayCodeFromDateTime(endDateTime)

                var currDay = startDateTime
                var dayCode = Formatter.getDayCodeFromDateTime(currDay)
                var currDayEvents = (dayEvents[dayCode] ?: ArrayList()).apply { add(it) }
                dayEvents.put(dayCode, currDayEvents)

                while (Formatter.getDayCodeFromDateTime(currDay) != endCode) {
                    currDay = currDay.plusDays(1)
                    dayCode = Formatter.getDayCodeFromDateTime(currDay)
                    currDayEvents = (dayEvents[dayCode] ?: ArrayList()).apply { add(it) }
                    dayEvents.put(dayCode, currDayEvents)
                }
            }

            days.filter { dayEvents.keys.contains(it.code) }.forEach {
                it.dayEvents = dayEvents[it.code]!!
            }
            mCallback.updateMonthlyCalendar(mContext, monthName, days, true)
        }
    }

    private fun isToday(targetDate: DateTime, curDayInMonth: Int): Boolean {
        return if (curDayInMonth > targetDate.dayOfMonth().maximumValue)
            false
        else
            targetDate.withDayOfMonth(curDayInMonth).toString(Formatter.DAYCODE_PATTERN) == mToday
    }

    private val monthName: String
        get() {
            var month = Formatter.getMonthName(mContext, mTargetDate.monthOfYear)
            val targetYear = mTargetDate.toString(YEAR_PATTERN)
            if (targetYear != DateTime().toString(YEAR_PATTERN)) {
                month += " $targetYear"
            }
            return month
        }

    private fun gotEvents(events: ArrayList<Event>) {
        mEvents = if (mFilterEventTypes) {
            mContext.getFilteredEvents(events) as ArrayList<Event>
        } else {
            events
        }

        getDays(true)
    }
}
