package com.simplemobiletools.calendar.activities

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.dialogs.*
import com.simplemobiletools.calendar.extensions.*
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.helpers.Formatter
import com.simplemobiletools.calendar.models.CalDAVCalendar
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.models.RadioItem
import kotlinx.android.synthetic.main.activity_event.*
import org.joda.time.DateTime
import java.util.*

class EventActivity : SimpleActivity(), DBHelper.EventUpdateListener {
    companion object {
        val STORED_LOCALLY_ONLY = 0
    }

    private var mReminder1Minutes = 0
    private var mReminder2Minutes = 0
    private var mReminder3Minutes = 0
    private var mRepeatInterval = 0
    private var mRepeatLimit = 0
    private var mRepeatRule = 0
    private var mEventTypeId = DBHelper.REGULAR_EVENT_TYPE_ID
    private var mDialogTheme = 0
    private var mEventOccurrenceTS = 0
    private var mEventCalendarId = STORED_LOCALLY_ONLY
    private var wasActivityInitialized = false

    lateinit var mEventStartDateTime: DateTime
    lateinit var mEventEndDateTime: DateTime
    lateinit var mEvent: Event

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event)

        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_cross)
        val intent = intent ?: return
        mDialogTheme = getAppropriateTheme()

        val eventId = intent.getIntExtra(EVENT_ID, 0)
        val event = dbHelper.getEventWithId(eventId)

        if (eventId != 0 && event == null) {
            finish()
            return
        }

        if (event != null) {
            mEvent = event
            mEventOccurrenceTS = intent.getIntExtra(EVENT_OCCURRENCE_TS, 0)
            setupEditEvent()
        } else {
            mEvent = Event()
            mReminder1Minutes = config.defaultReminderMinutes
            mReminder2Minutes = -1
            mReminder3Minutes = -1
            val startTS = intent.getIntExtra(NEW_EVENT_START_TS, 0)
            if (startTS == 0) {
                return
            }

            setupNewEvent(Formatter.getDateTimeFromTS(startTS))
        }

        checkReminderTexts()
        updateRepetitionText()
        updateStartTexts()
        updateEndTexts()
        updateEventType()
        updateCalDAVCalendar()
        updateLocation()

        event_start_date.setOnClickListener { setupStartDate() }
        event_start_time.setOnClickListener { setupStartTime() }
        event_end_date.setOnClickListener { setupEndDate() }
        event_end_time.setOnClickListener { setupEndTime() }

        event_all_day.setOnCheckedChangeListener { compoundButton, isChecked -> toggleAllDay(isChecked) }
        event_repetition.setOnClickListener { showRepeatIntervalDialog() }
        event_repetition_rule_holder.setOnClickListener { showRepetitionRuleDialog() }
        event_repetition_limit_holder.setOnClickListener { showRepetitionTypePicker() }

        event_reminder_1.setOnClickListener { showReminder1Dialog() }
        event_reminder_2.setOnClickListener { showReminder2Dialog() }
        event_reminder_3.setOnClickListener { showReminder3Dialog() }

        event_type_holder.setOnClickListener { showEventTypeDialog() }

        if (mEvent.flags and FLAG_ALL_DAY != 0)
            event_all_day.toggle()

        updateTextColors(event_scrollview)
        updateIconColors()
        wasActivityInitialized = true
    }

    private fun setupEditEvent() {
        val realStart = if (mEventOccurrenceTS == 0) mEvent.startTS else mEventOccurrenceTS
        val duration = mEvent.endTS - mEvent.startTS
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        title = resources.getString(R.string.edit_event)
        mEventStartDateTime = Formatter.getDateTimeFromTS(realStart)
        mEventEndDateTime = Formatter.getDateTimeFromTS(realStart + duration)
        event_title.setText(mEvent.title)
        event_location.setText(mEvent.location)
        event_description.setText(mEvent.description)
        event_description.movementMethod = LinkMovementMethod.getInstance()

        mReminder1Minutes = mEvent.reminder1Minutes
        mReminder2Minutes = mEvent.reminder2Minutes
        mReminder3Minutes = mEvent.reminder3Minutes
        mRepeatInterval = mEvent.repeatInterval
        mRepeatLimit = mEvent.repeatLimit
        mRepeatRule = mEvent.repeatRule
        mEventTypeId = mEvent.eventType
        mEventCalendarId = mEvent.getCalDAVCalendarId()
        checkRepeatTexts(mRepeatInterval)
    }

    private fun setupNewEvent(dateTime: DateTime) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        title = resources.getString(R.string.new_event)
        mEventStartDateTime = dateTime

        val addHours = if (intent.getBooleanExtra(NEW_EVENT_SET_HOUR_DURATION, false)) 1 else 0
        mEventEndDateTime = mEventStartDateTime.plusHours(addHours)

        val isLastCaldavCalendarOK = config.caldavSync && config.getSyncedCalendarIdsAsList().contains(config.lastUsedCaldavCalendar.toString())
        mEventCalendarId = if (isLastCaldavCalendarOK) config.lastUsedCaldavCalendar else STORED_LOCALLY_ONLY
    }

    private fun showReminder1Dialog() {
        showEventReminderDialog(mReminder1Minutes) {
            mReminder1Minutes = it
            checkReminderTexts()
        }
    }

    private fun showReminder2Dialog() {
        showEventReminderDialog(mReminder2Minutes) {
            mReminder2Minutes = it
            checkReminderTexts()
        }
    }

    private fun showReminder3Dialog() {
        showEventReminderDialog(mReminder3Minutes) {
            mReminder3Minutes = it
            checkReminderTexts()
        }
    }

    private fun showRepeatIntervalDialog() {
        showEventRepeatIntervalDialog(mRepeatInterval) {
            setRepeatInterval(it)
        }
    }

    private fun setRepeatInterval(interval: Int) {
        mRepeatInterval = interval
        updateRepetitionText()
        checkRepeatTexts(interval)

        if (mRepeatInterval.isXWeeklyRepetition()) {
            setRepeatRule(Math.pow(2.0, (mEventStartDateTime.dayOfWeek - 1).toDouble()).toInt())
        } else if (mRepeatInterval.isXMonthlyRepetition()) {
            setRepeatRule(REPEAT_MONTH_SAME_DAY)
        }
    }

    private fun checkRepeatTexts(limit: Int) {
        event_repetition_limit_holder.beGoneIf(limit == 0)
        checkRepetitionLimitText()

        event_repetition_rule_holder.beVisibleIf(mRepeatInterval.isXWeeklyRepetition() || mRepeatInterval.isXMonthlyRepetition())
        checkRepetitionRuleText()
    }

    private fun showRepetitionTypePicker() {
        hideKeyboard()
        RepeatLimitTypePickerDialog(this, mRepeatLimit, mEventStartDateTime.seconds()) {
            setRepeatLimit(it)
        }
    }

    private fun setRepeatLimit(limit: Int) {
        mRepeatLimit = limit
        checkRepetitionLimitText()
    }

    private fun checkRepetitionLimitText() {
        event_repetition_limit.text = when {
            mRepeatLimit == 0 -> {
                event_repetition_limit_label.text = getString(R.string.repeat)
                resources.getString(R.string.forever)
            }
            mRepeatLimit > 0 -> {
                event_repetition_limit_label.text = getString(R.string.repeat_till)
                val repeatLimitDateTime = Formatter.getDateTimeFromTS(mRepeatLimit)
                Formatter.getFullDate(applicationContext, repeatLimitDateTime)
            }
            else -> {
                event_repetition_limit_label.text = getString(R.string.repeat)
                "${-mRepeatLimit} ${getString(R.string.times)}"
            }
        }
    }

    private fun showRepetitionRuleDialog() {
        hideKeyboard()
        if (mRepeatInterval.isXWeeklyRepetition()) {
            RepeatRuleWeeklyDialog(this, mRepeatRule) {
                setRepeatRule(it)
            }
        } else if (mRepeatInterval.isXMonthlyRepetition()) {
            val items = arrayListOf(
                    RadioItem(REPEAT_MONTH_SAME_DAY, getString(R.string.repeat_on_the_same_day)),
                    RadioItem(REPEAT_MONTH_EVERY_XTH_DAY, getRepeatXthDayString(true)))

            if (isLastDayOfTheMonth()) {
                items.add(RadioItem(REPEAT_MONTH_LAST_DAY, getString(R.string.repeat_on_the_last_day)))
            }

            RadioGroupDialog(this, items, mRepeatRule) {
                setRepeatRule(it as Int)
            }
        }
    }

    private fun isLastDayOfTheMonth() = mEventStartDateTime.dayOfMonth == mEventStartDateTime.dayOfMonth().withMaximumValue().dayOfMonth

    private fun getRepeatXthDayString(includeBase: Boolean): String {
        val dayOfWeek = mEventStartDateTime.dayOfWeek
        val base = getBaseString(dayOfWeek)
        val order = getOrderString()
        val dayString = getDayString(dayOfWeek)
        return if (includeBase) {
            "$base $order $dayString"
        } else {
            val everyString = getString(if (isMaleGender(mEventStartDateTime.dayOfWeek)) R.string.every_m else R.string.every_f)
            "$everyString $order $dayString"
        }
    }

    private fun getBaseString(day: Int): String {
        return getString(if (isMaleGender(day)) {
            R.string.repeat_every_m
        } else {
            R.string.repeat_every_f
        })
    }

    private fun isMaleGender(day: Int) = day == 1 || day == 2 || day == 4 || day == 5

    private fun getOrderString(): String {
        val dayOfMonth = mEventStartDateTime.dayOfMonth
        var order = (dayOfMonth - 1) / 7 + 1
        if (mEventStartDateTime.monthOfYear != mEventStartDateTime.plusDays(7).monthOfYear) {
            order = -1
        }

        val isMale = isMaleGender(mEventStartDateTime.dayOfWeek)
        return getString(when (order) {
            1 -> if (isMale) R.string.first_m else R.string.first_f
            2 -> if (isMale) R.string.second_m else R.string.second_f
            3 -> if (isMale) R.string.third_m else R.string.third_f
            4 -> if (isMale) R.string.fourth_m else R.string.fourth_f
            else -> if (isMale) R.string.last_m else R.string.last_f
        })
    }

    private fun getDayString(day: Int): String {
        return getString(when (day) {
            1 -> R.string.monday_alt
            2 -> R.string.tuesday_alt
            3 -> R.string.wednesday_alt
            4 -> R.string.thursday_alt
            5 -> R.string.friday_alt
            6 -> R.string.saturday_alt
            else -> R.string.sunday_alt
        })
    }

    private fun setRepeatRule(rule: Int) {
        mRepeatRule = rule
        checkRepetitionRuleText()
        if (rule == 0) {
            setRepeatInterval(0)
        }
    }

    private fun checkRepetitionRuleText() {
        if (mRepeatInterval.isXWeeklyRepetition()) {
            event_repetition_rule.text = if (mRepeatRule == EVERY_DAY) getString(R.string.every_day) else getSelectedDaysString()
        } else if (mRepeatInterval.isXMonthlyRepetition()) {
            event_repetition_rule_label.text = getString(if (mRepeatRule == REPEAT_MONTH_EVERY_XTH_DAY) R.string.repeat else R.string.repeat_on)
            event_repetition_rule.text = getMonthlyRepetitionRuleText()
        }
    }

    private fun getSelectedDaysString(): String {
        var days = ""
        if (mRepeatRule and MONDAY != 0)
            days += "${getString(R.string.monday).substringTo(3)}, "
        if (mRepeatRule and TUESDAY != 0)
            days += "${getString(R.string.tuesday).substringTo(3)}, "
        if (mRepeatRule and WEDNESDAY != 0)
            days += "${getString(R.string.wednesday).substringTo(3)}, "
        if (mRepeatRule and THURSDAY != 0)
            days += "${getString(R.string.thursday).substringTo(3)}, "
        if (mRepeatRule and FRIDAY != 0)
            days += "${getString(R.string.friday).substringTo(3)}, "
        if (mRepeatRule and SATURDAY != 0)
            days += "${getString(R.string.saturday).substringTo(3)}, "
        if (mRepeatRule and SUNDAY != 0)
            days += "${getString(R.string.sunday).substringTo(3)}, "

        return days.trim().trimEnd(',')
    }

    private fun getMonthlyRepetitionRuleText() = when (mRepeatRule) {
        REPEAT_MONTH_SAME_DAY -> getString(R.string.the_same_day)
        REPEAT_MONTH_LAST_DAY -> getString(R.string.the_last_day)
        else -> getRepeatXthDayString(false)
    }

    private fun showEventTypeDialog() {
        hideKeyboard()
        SelectEventTypeDialog(this, mEventTypeId) {
            mEventTypeId = it
            updateEventType()
        }
    }

    private fun checkReminderTexts() {
        updateReminder1Text()
        updateReminder2Text()
        updateReminder3Text()
    }

    private fun updateReminder1Text() {
        event_reminder_1.text = getFormattedMinutes(mReminder1Minutes)
        if (mReminder1Minutes == REMINDER_OFF) {
            mReminder2Minutes = REMINDER_OFF
            mReminder3Minutes = REMINDER_OFF
        }
    }

    private fun updateReminder2Text() {
        event_reminder_2.apply {
            beGoneIf(mReminder1Minutes == REMINDER_OFF)
            if (mReminder2Minutes == REMINDER_OFF) {
                text = resources.getString(R.string.add_another_reminder)
                alpha = 0.4f
                mReminder3Minutes = REMINDER_OFF
            } else {
                text = getFormattedMinutes(mReminder2Minutes)
                alpha = 1f
            }
        }
    }

    private fun updateReminder3Text() {
        event_reminder_3.apply {
            beGoneIf(mReminder2Minutes == REMINDER_OFF || mReminder1Minutes == REMINDER_OFF)
            if (mReminder3Minutes == REMINDER_OFF) {
                text = resources.getString(R.string.add_another_reminder)
                alpha = 0.4f
            } else {
                text = getFormattedMinutes(mReminder3Minutes)
                alpha = 1f
            }
        }
    }

    private fun updateRepetitionText() {
        event_repetition.text = getRepetitionText(mRepeatInterval)
    }

    private fun updateEventType() {
        val eventType = dbHelper.getEventType(mEventTypeId)
        if (eventType != null) {
            event_type.text = eventType.title
            event_type_color.setBackgroundWithStroke(eventType.color, config.backgroundColor)
        }
    }

    private fun updateCalDAVCalendar() {
        if (config.caldavSync) {
            event_caldav_calendar_image.beVisible()
            event_caldav_calendar_holder.beVisible()
            event_caldav_calendar_divider.beVisible()

            val calendars = CalDAVHandler(applicationContext).getCalDAVCalendars().filter {
                it.canWrite() && config.getSyncedCalendarIdsAsList().contains(it.id.toString())
            }
            updateCurrentCalendarInfo(if (mEventCalendarId == STORED_LOCALLY_ONLY) null else getCalendarWithId(calendars, getCalendarId()))

            event_caldav_calendar_holder.setOnClickListener {
                hideKeyboard()
                SelectEventCalendarDialog(this, calendars, mEventCalendarId) {
                    if (mEventCalendarId != STORED_LOCALLY_ONLY && it == STORED_LOCALLY_ONLY) {
                        mEventTypeId = DBHelper.REGULAR_EVENT_TYPE_ID
                        updateEventType()
                    }
                    mEventCalendarId = it
                    config.lastUsedCaldavCalendar = it
                    updateCurrentCalendarInfo(getCalendarWithId(calendars, it))
                }
            }
        } else {
            updateCurrentCalendarInfo(null)
        }
    }

    private fun getCalendarId() = if (mEvent.source == SOURCE_SIMPLE_CALENDAR) config.lastUsedCaldavCalendar else mEvent.getCalDAVCalendarId()

    private fun getCalendarWithId(calendars: List<CalDAVCalendar>, calendarId: Int): CalDAVCalendar? =
            calendars.firstOrNull { it.id == calendarId }

    private fun updateCurrentCalendarInfo(currentCalendar: CalDAVCalendar?) {
        event_type_image.beVisibleIf(currentCalendar == null)
        event_type_holder.beVisibleIf(currentCalendar == null)
        event_caldav_calendar_divider.beVisibleIf(currentCalendar == null)
        event_caldav_calendar_email.beGoneIf(currentCalendar == null)

        if (currentCalendar == null) {
            event_caldav_calendar_name.apply {
                text = getString(R.string.store_locally_only)
                setPadding(paddingLeft, paddingTop, paddingRight, resources.getDimension(R.dimen.medium_margin).toInt())
            }
        } else {
            event_caldav_calendar_email.text = currentCalendar.accountName
            event_caldav_calendar_name.apply {
                text = currentCalendar.displayName
                setPadding(paddingLeft, paddingTop, paddingRight, resources.getDimension(R.dimen.tiny_margin).toInt())
            }
        }
    }

    private fun updateLocation() {
        event_location.setText(mEvent.location)
    }

    private fun toggleAllDay(isChecked: Boolean) {
        hideKeyboard()
        event_start_time.beGoneIf(isChecked)
        event_end_time.beGoneIf(isChecked)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_event, menu)
        updateMenuTextSize(resources, menu)
        if (wasActivityInitialized) {
            menu.findItem(R.id.delete).isVisible = mDialogTheme != 0 && mEvent.id != 0
            menu.findItem(R.id.share).isVisible = mDialogTheme != 0 && mEvent.id != 0
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.save -> saveEvent()
            R.id.delete -> deleteEvent()
            R.id.share -> shareEvent()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun shareEvent() {
        shareEvents(arrayListOf(mEvent.id))
    }

    private fun deleteEvent() {
        DeleteEventDialog(this, arrayListOf(mEvent.id)) {
            if (it) {
                dbHelper.deleteEvents(arrayOf(mEvent.id.toString()), true)
            } else {
                dbHelper.addEventRepeatException(mEvent.id, mEventOccurrenceTS)
            }
            finish()
        }
    }

    private fun saveEvent() {
        val newTitle = event_title.value
        if (newTitle.isEmpty()) {
            toast(R.string.title_empty)
            event_title.requestFocus()
            return
        }

        val newStartTS = mEventStartDateTime.withSecondOfMinute(0).withMillisOfSecond(0).seconds()
        val newEndTS = mEventEndDateTime.withSecondOfMinute(0).withMillisOfSecond(0).seconds()

        if (newStartTS > newEndTS) {
            toast(R.string.end_before_start)
            return
        }

        val wasRepeatable = mEvent.repeatInterval > 0
        val oldSource = mEvent.source
        val newImportId = if (mEvent.id != 0) mEvent.importId else UUID.randomUUID().toString().replace("-", "") + System.currentTimeMillis().toString()

        val newEventType = if (!config.caldavSync || config.lastUsedCaldavCalendar == 0 || mEventCalendarId == STORED_LOCALLY_ONLY) {
            mEventTypeId
        } else {
            dbHelper.getEventTypeWithCalDAVCalendarId(config.lastUsedCaldavCalendar)?.id ?: DBHelper.REGULAR_EVENT_TYPE_ID
        }

        val newSource = if (!config.caldavSync || config.lastUsedCaldavCalendar == 0 || mEventCalendarId == STORED_LOCALLY_ONLY) {
            SOURCE_SIMPLE_CALENDAR
        } else {
            "$CALDAV-${config.lastUsedCaldavCalendar}"
        }

        val reminders = sortedSetOf(mReminder1Minutes, mReminder2Minutes, mReminder3Minutes).filter { it != REMINDER_OFF }
        mEvent.apply {
            startTS = newStartTS
            endTS = newEndTS
            title = newTitle
            description = event_description.value
            reminder1Minutes = reminders.elementAtOrElse(0) { REMINDER_OFF }
            reminder2Minutes = reminders.elementAtOrElse(1) { REMINDER_OFF }
            reminder3Minutes = reminders.elementAtOrElse(2) { REMINDER_OFF }
            repeatInterval = mRepeatInterval
            importId = newImportId
            flags = if (event_all_day.isChecked) (mEvent.flags or FLAG_ALL_DAY) else (mEvent.flags.removeFlag(FLAG_ALL_DAY))
            repeatLimit = if (repeatInterval == 0) 0 else mRepeatLimit
            repeatRule = mRepeatRule
            eventType = newEventType
            offset = getCurrentOffset()
            isDstIncluded = TimeZone.getDefault().inDaylightTime(Date())
            lastUpdated = System.currentTimeMillis()
            source = newSource
            location = event_location.value
        }

        // recreate the event if it was moved in a different CalDAV calendar
        if (mEvent.id != 0 && oldSource != newSource) {
            dbHelper.deleteEvents(arrayOf(mEvent.id.toString()), true)
            mEvent.id = 0
        }

        storeEvent(wasRepeatable)
    }

    private fun storeEvent(wasRepeatable: Boolean) {
        if (mEvent.id == 0) {
            dbHelper.insert(mEvent, true) {
                if (DateTime.now().isAfter(mEventStartDateTime.millis)) {
                    notifyEvent(mEvent)
                } else {
                    toast(R.string.event_added)
                }

                finish()
            }
        } else {
            if (mRepeatInterval > 0 && wasRepeatable) {
                EditRepeatingEventDialog(this) {
                    if (it) {
                        dbHelper.update(mEvent, true) {
                            eventUpdated()
                        }
                    } else {
                        dbHelper.addEventRepeatException(mEvent.id, mEventOccurrenceTS)
                        mEvent.parentId = mEvent.id
                        mEvent.id = 0
                        dbHelper.insert(mEvent, true) {
                            toast(R.string.event_updated)
                            finish()
                        }
                    }
                }
            } else {
                dbHelper.update(mEvent, true) {
                    eventUpdated()
                }
            }
        }
    }

    private fun eventUpdated() {
        toast(R.string.event_updated)
        finish()
    }

    private fun updateStartTexts() {
        updateStartDateText()
        updateStartTimeText()
    }

    private fun updateStartDateText() {
        event_start_date.text = Formatter.getDate(applicationContext, mEventStartDateTime)
        checkStartEndValidity()
    }

    private fun updateStartTimeText() {
        event_start_time.text = Formatter.getTime(this, mEventStartDateTime)
        checkStartEndValidity()
    }

    private fun updateEndTexts() {
        updateEndDateText()
        updateEndTimeText()
    }

    private fun updateEndDateText() {
        event_end_date.text = Formatter.getDate(applicationContext, mEventEndDateTime)
        checkStartEndValidity()
    }

    private fun updateEndTimeText() {
        event_end_time.text = Formatter.getTime(this, mEventEndDateTime)
        checkStartEndValidity()
    }

    private fun checkStartEndValidity() {
        val textColor = if (mEventStartDateTime.isAfter(mEventEndDateTime)) resources.getColor(R.color.red_text) else config.textColor
        event_end_date.setTextColor(textColor)
        event_end_time.setTextColor(textColor)
    }

    @SuppressLint("NewApi")
    private fun setupStartDate() {
        hideKeyboard()
        config.backgroundColor.getContrastColor()
        val datepicker = DatePickerDialog(this, mDialogTheme, startDateSetListener, mEventStartDateTime.year, mEventStartDateTime.monthOfYear - 1,
                mEventStartDateTime.dayOfMonth)

        if (isLollipopPlus()) {
            datepicker.datePicker.firstDayOfWeek = if (config.isSundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        }

        datepicker.show()
    }

    private fun setupStartTime() {
        hideKeyboard()
        TimePickerDialog(this, mDialogTheme, startTimeSetListener, mEventStartDateTime.hourOfDay, mEventStartDateTime.minuteOfHour, config.use24hourFormat).show()
    }

    @SuppressLint("NewApi")
    private fun setupEndDate() {
        hideKeyboard()
        val datepicker = DatePickerDialog(this, mDialogTheme, endDateSetListener, mEventEndDateTime.year, mEventEndDateTime.monthOfYear - 1,
                mEventEndDateTime.dayOfMonth)

        if (isLollipopPlus()) {
            datepicker.datePicker.firstDayOfWeek = if (config.isSundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        }

        datepicker.show()
    }

    private fun setupEndTime() {
        hideKeyboard()
        TimePickerDialog(this, mDialogTheme, endTimeSetListener, mEventEndDateTime.hourOfDay, mEventEndDateTime.minuteOfHour, config.use24hourFormat).show()
    }

    private val startDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth ->
        dateSet(year, monthOfYear, dayOfMonth, true)
    }

    private val startTimeSetListener = TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute ->
        timeSet(hourOfDay, minute, true)
    }

    private val endDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth -> dateSet(year, monthOfYear, dayOfMonth, false) }

    private val endTimeSetListener = TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute -> timeSet(hourOfDay, minute, false) }

    private fun dateSet(year: Int, month: Int, day: Int, isStart: Boolean) {
        if (isStart) {
            val diff = mEventEndDateTime.seconds() - mEventStartDateTime.seconds()

            mEventStartDateTime = mEventStartDateTime.withDate(year, month + 1, day)
            updateStartDateText()
            checkRepeatRule()

            mEventEndDateTime = mEventStartDateTime.plusSeconds(diff)
            updateEndTexts()
        } else {
            mEventEndDateTime = mEventEndDateTime.withDate(year, month + 1, day)
            updateEndDateText()
        }
    }

    private fun timeSet(hours: Int, minutes: Int, isStart: Boolean) {
        if (isStart) {
            val diff = mEventEndDateTime.seconds() - mEventStartDateTime.seconds()

            mEventStartDateTime = mEventStartDateTime.withHourOfDay(hours).withMinuteOfHour(minutes)
            updateStartTimeText()

            mEventEndDateTime = mEventStartDateTime.plusSeconds(diff)
            updateEndTexts()
        } else {
            mEventEndDateTime = mEventEndDateTime.withHourOfDay(hours).withMinuteOfHour(minutes)
            updateEndTimeText()
        }
    }

    private fun checkRepeatRule() {
        if (mRepeatInterval.isXWeeklyRepetition()) {
            val day = mRepeatRule
            if (day == MONDAY || day == TUESDAY || day == WEDNESDAY || day == THURSDAY || day == FRIDAY || day == SATURDAY || day == SUNDAY) {
                setRepeatRule(Math.pow(2.0, (mEventStartDateTime.dayOfWeek - 1).toDouble()).toInt())
            }
        } else if (mRepeatInterval.isXMonthlyRepetition()) {
            if (mRepeatRule == REPEAT_MONTH_LAST_DAY && !isLastDayOfTheMonth())
                mRepeatRule = REPEAT_MONTH_SAME_DAY
            checkRepetitionRuleText()
        }
    }

    private fun updateIconColors() {
        val textColor = config.textColor
        event_time_image.applyColorFilter(textColor)
        event_repetition_image.applyColorFilter(textColor)
        event_reminder_image.applyColorFilter(textColor)
        event_type_image.applyColorFilter(textColor)
        event_caldav_calendar_image.applyColorFilter(textColor)
    }

    override fun eventInserted(event: Event) {
    }

    override fun eventsDeleted(cnt: Int) {
    }

    override fun gotEvents(events: MutableList<Event>) {
    }
}
