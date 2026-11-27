package app.qitou.bridge

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Shared light/dark dialog surface used by every QITOU secondary popup. */
object CarDialogs {
    fun builder(context: Context): AlertDialog.Builder =
        StyledBuilder(context)

    private class StyledBuilder(context: Context) :
        AlertDialog.Builder(context, R.style.Theme_QITOU_Dialog) {
        override fun create(): AlertDialog {
            val dialog = super.create()
            dialog.setOnShowListener {
                val contextual = dialog.context
                val actionColors: ColorStateList = ContextCompat.getColorStateList(
                    contextual,
                    R.color.qitou_dialog_button,
                ) ?: ColorStateList.valueOf(
                    CarAppTheme.color(contextual, R.color.dialog_action),
                )
                dialog.window?.decorView?.let { decor ->
                    decor.findViewById<TextView>(android.R.id.message)?.setTextColor(
                        CarAppTheme.color(contextual, R.color.dialog_message),
                    )
                    styleTextViews(decor, contextual)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionColors)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(actionColors)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(actionColors)
            }
            return dialog
        }

        private fun styleTextViews(view: View, context: Context) {
            if (view is TextView) {
                when (view.id) {
                    android.R.id.message -> view.setTextColor(
                        CarAppTheme.color(context, R.color.dialog_message),
                    )
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    styleTextViews(view.getChildAt(index), context)
                }
            }
        }
    }
}
