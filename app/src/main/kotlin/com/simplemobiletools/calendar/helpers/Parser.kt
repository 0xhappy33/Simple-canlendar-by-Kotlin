package com.simplemobiletools.calendar.helpers

import com.simplemobiletools.calendar.extensions.isXMonthlyRepetition
import com.simplemobiletools.calendar.extensions.isXWeeklyRepetition
import com.simplemobiletools.calendar.extensions.seconds
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.calendar.models.RepeatRule
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

class Parser {
    // from RRULE:FREQ=DAILY;COUNT=5 to Daily, 5x...
    fun parseRepeatInterval(fullString: String, startTS: Int): RepeatRule {
        val parts = fullString.split(";")
        var repeatInterval = 0
        var repeatRule = 0
        var repeatLimit = 0
        if (fullString.isEmpty()) {
            return RepeatRule(repeatInterval, repeatRule, repeatLimit)
        }

        for (part in parts) {
            val keyValue = part.split("=")
            val key = keyValue[0]
            val value = keyValue[1]
            if (key == FREQ) {
                repeatInterval = getFrequencySeconds(value)
                if (value == WEEKLY) {
                    val start = Formatter.getDateTimeFromTS(startTS)
                    repeatRule = Math.pow(2.0, (start.dayOfWeek - 1).toDouble()).toInt()
                } else if (value == MONTHLY) {
                    repeatRule = REPEAT_MONTH_SAME_DAY
                }
            } else if (key == COUNT) {
                repeatLimit = -value.toInt()
            } else if (key == UNTIL) {
                repeatLimit = parseDateTimeValue(value)
            } else if (key == INTERVAL) {
                repeatInterval *= value.toInt()
            } else if (key == BYDAY) {
                if (repeatInterval.isXWeeklyRepetition()) {
                    repeatRule = handleRepeatRule(value)
                } else if (repeatInterval.isXMonthlyRepetition()) {
                    repeatRule = REPEAT_MONTH_EVERY_XTH_DAY
                }
            } else if (key == BYMONTHDAY && value.toInt() == -1) {
                repeatRule = REPEAT_MONTH_LAST_DAY
            }
        }
        return RepeatRule(repeatInterval, repeatRule, repeatLimit)
    }

    private fun getFrequencySeconds(interval: String) = when (interval) {
        DAILY -> DAY
        WEEKLY -> WEEK
        MONTHLY -> MONTH
        YEARLY -> YEAR
        else -> 0
    }

    private fun handleRepeatRule(value: String): Int {
        var newRepeatRule = 0
        if (value.contains(MO))
            newRepeatRule = newRepeatRule or MONDAY
        if (value.contains(TU))
            newRepeatRule = newRepeatRule or TUESDAY
        if (value.contains(WE))
            newRepeatRule = newRepeatRule or WEDNESDAY
        if (value.contains(TH))
            newRepeatRule = newRepeatRule or THURSDAY
        if (value.contains(FR))
            newRepeatRule = newRepeatRule or FRIDAY
        if (value.contains(SA))
            newRepeatRule = newRepeatRule or SATURDAY
        if (value.contains(SU))
            newRepeatRule = newRepeatRule or SUNDAY
        return newRepeatRule
    }

    fun parseDateTimeValue(value: String): Int {
        val edited = value.replace("T", "").replace("Z", "")
        return if (edited.length == 14) {
            parseLongFormat(edited, value.endsWith("Z"))
        } else {
            val dateTimeFormat = DateTimeFormat.forPattern("yyyyMMdd")
            dateTimeFormat.parseDateTime(edited).withZoneRetainFields(DateTimeZone.getDefault()).withHourOfDay(1).seconds()
        }
    }

    private fun parseLongFormat(digitString: String, useUTC: Boolean): Int {
        val dateTimeFormat = DateTimeFormat.forPattern("yyyyMMddHHmmss")
        val dateTimeZone = if (useUTC) DateTimeZone.UTC else DateTimeZone.getDefault()
        return dateTimeFormat.parseDateTime(digitString).withZoneRetainFields(dateTimeZone).seconds()
    }

    // from Daily, 5x... to RRULE:FREQ=DAILY;COUNT=5
    fun getRepeatCode(event: Event): String {
        val repeatInterval = event.repeatInterval
        if (repeatInterval == 0)
            return ""

        val freq = getFreq(repeatInterval)
        val interval = getInterval(repeatInterval)
        val repeatLimit = getRepeatLimitString(event)
        val byDay = getByDay(event)
        return "$FREQ=$freq;$INTERVAL=$interval$repeatLimit$byDay"
    }

    private fun getFreq(interval: Int) = when {
        interval % YEAR == 0 -> YEARLY
        interval % MONTH == 0 -> MONTHLY
        interval % WEEK == 0 -> WEEKLY
        else -> DAILY
    }

    private fun getInterval(interval: Int) = when {
        interval % YEAR == 0 -> interval / YEAR
        interval % MONTH == 0 -> interval / MONTH
        interval % WEEK == 0 -> interval / WEEK
        else -> interval / DAY
    }

    private fun getRepeatLimitString(event: Event) = when {
        event.repeatLimit == 0 -> ""
        event.repeatLimit < 0 -> ";$COUNT=${-event.repeatLimit}"
        else -> ";$UNTIL=${Formatter.getDayCodeFromTS(event.repeatLimit)}"
    }

    private fun getByDay(event: Event) = when {
        event.repeatInterval.isXWeeklyRepetition() -> {
            val days = getByDayString(event.repeatRule)
            ";$BYDAY=$days"
        }
        event.repeatInterval.isXMonthlyRepetition() -> when {
            event.repeatRule == REPEAT_MONTH_LAST_DAY -> ";$BYMONTHDAY=-1"
            event.repeatRule == REPEAT_MONTH_EVERY_XTH_DAY -> {
                val start = Formatter.getDateTimeFromTS(event.startTS)
                val dayOfMonth = start.dayOfMonth
                val order = (dayOfMonth - 1) / 7 + 1
                val day = getDayLetters(start.dayOfWeek)
                ";$BYDAY=$order$day"
            }
            else -> ""
        }
        else -> ""
    }

    private fun getByDayString(rule: Int): String {
        var result = ""
        if (rule and MONDAY != 0)
            result += "$MO,"
        if (rule and TUESDAY != 0)
            result += "$TU,"
        if (rule and WEDNESDAY != 0)
            result += "$WE,"
        if (rule and THURSDAY != 0)
            result += "$TH,"
        if (rule and FRIDAY != 0)
            result += "$FR,"
        if (rule and SATURDAY != 0)
            result += "$SA,"
        if (rule and SUNDAY != 0)
            result += "$SU,"
        return result.trimEnd(',')
    }

    private fun getDayLetters(dayOfWeek: Int) = when (dayOfWeek) {
        1 -> MO
        2 -> TU
        3 -> WE
        4 -> TH
        5 -> FR
        6 -> SA
        else -> SU
    }

    // from P0DT1H5M0S to 3900 (seconds)
    fun parseDurationSeconds(duration: String): Int {
        val weeks = getDurationValue(duration, "W")
        val days = getDurationValue(duration, "D")
        val hours = getDurationValue(duration, "H")
        val minutes = getDurationValue(duration, "M")
        val seconds = getDurationValue(duration, "S")

        val minSecs = 60
        val hourSecs = minSecs * 60
        val daySecs = hourSecs * 24
        val weekSecs = daySecs * 7

        return seconds + (minutes * minSecs) + (hours * hourSecs) + (days * daySecs) + (weeks * weekSecs)
    }

    private fun getDurationValue(duration: String, char: String) = Regex("[0-9]+(?=$char)").find(duration)?.value?.toInt() ?: 0

    // from 65 to P0DT1H5M0S
    fun getDurationCode(minutes: Int): String {
        var days = 0
        var hours = 0
        var remainder = minutes
        if (remainder >= DAY_MINUTES) {
            days = Math.floor((remainder / DAY_MINUTES).toDouble()).toInt()
            remainder -= days * DAY_MINUTES
        }
        if (remainder >= 60) {
            hours = Math.floor((remainder / 60).toDouble()).toInt()
            remainder -= hours * 60
        }
        return "P${days}DT${hours}H${remainder}M0S"
    }
}
