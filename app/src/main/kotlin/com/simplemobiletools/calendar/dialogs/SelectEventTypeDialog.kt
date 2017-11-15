package com.simplemobiletools.calendar.dialogs

import android.app.Activity
import android.graphics.Color
import android.support.v7.app.AlertDialog
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.extensions.config
import com.simplemobiletools.calendar.extensions.dbHelper
import com.simplemobiletools.calendar.models.EventType
import com.simplemobiletools.commons.extensions.hideKeyboard
import com.simplemobiletools.commons.extensions.setBackgroundWithStroke
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.updateTextColors
import kotlinx.android.synthetic.main.dialog_select_radio_group.view.*
import kotlinx.android.synthetic.main.radio_button_with_color.view.*
import java.util.*

class SelectEventTypeDialog(val activity: Activity, val currEventType: Int, val callback: (checkedId: Int) -> Unit) {
    private val NEW_TYPE_ID = -2

    private val dialog: AlertDialog?
    private val radioGroup: RadioGroup
    private var wasInit = false
    private var eventTypes = ArrayList<EventType>()

    init {
        val view = activity.layoutInflater.inflate(R.layout.dialog_select_radio_group, null) as ViewGroup
        radioGroup = view.dialog_radio_group

        activity.dbHelper.getEventTypes {
            eventTypes = it
            activity.runOnUiThread {
                eventTypes.filter { it.caldavCalendarId == 0 }.forEach {
                    addRadioButton(it.getDisplayTitle(), it.id, it.color)
                }
                addRadioButton(activity.getString(R.string.add_new_type), NEW_TYPE_ID, Color.TRANSPARENT)
                wasInit = true
                activity.updateTextColors(view.dialog_radio_holder)
            }
        }

        dialog = AlertDialog.Builder(activity)
                .create().apply {
            activity.setupDialogStuff(view, this)
        }
    }

    private fun addRadioButton(title: String, typeId: Int, color: Int) {
        val view = activity.layoutInflater.inflate(R.layout.radio_button_with_color, null)
        (view.dialog_radio_button as RadioButton).apply {
            text = title
            isChecked = typeId == currEventType
            id = typeId
        }

        if (color != Color.TRANSPARENT)
            view.dialog_radio_color.setBackgroundWithStroke(color, activity.config.backgroundColor)

        view.setOnClickListener { viewClicked(typeId) }
        radioGroup.addView(view, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun viewClicked(typeId: Int) {
        if (!wasInit)
            return

        if (typeId == NEW_TYPE_ID) {
            UpdateEventTypeDialog(activity) {
                callback(it)
                activity.hideKeyboard()
                dialog?.dismiss()
            }
        } else {
            callback(typeId)
            dialog?.dismiss()
        }
    }
}
