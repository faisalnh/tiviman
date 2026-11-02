package faisalnh.tiviman

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView

object GlassDialogs {
    fun builder(context: Context): AlertDialog.Builder = AlertDialog.Builder(context, R.style.GlassDialog)

    fun style(dialog: Dialog, blurBehind: Boolean = true) {
        val window = dialog.window ?: return
        val context = window.context
        val inset = GlassMaterial.dp(context, 16f)
        window.setBackgroundDrawable(InsetDrawable(
            GlassMaterial.panel(context, GlassVariant.REGULAR, GlassTokens.RADIUS_SHEET_DP), inset))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.45f)
        window.setElevation(GlassMaterial.dp(context, 12f).toFloat())
        styleView(window.decorView)
        if (dialog is AlertDialog) {
            dialog.listView?.let { styleList(it) }
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(GlassTokens.textSecondary)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .mapNotNull { dialog.getButton(it) }.forEach { button ->
                    (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                        params.marginStart = GlassMaterial.dp(context, 4f)
                        params.marginEnd = GlassMaterial.dp(context, 4f)
                        button.layoutParams = params
                    }
                }
        }
        if (blurBehind) GlassMaterial.applyWindowBlur(window)
    }

    private fun styleList(list: ListView) {
        list.selector = GlassMaterial.focusable(list.context, quietFill = false)
        list.divider = null
        list.dividerHeight = 0
        list.cacheColorHint = android.graphics.Color.TRANSPARENT
        list.setDrawSelectorOnTop(false)
    }

    private fun styleView(view: View) {
        val context = view.context
        when (view) {
            is EditText -> {
                view.backgroundTintList = null
                view.background = context.getDrawable(R.drawable.glass_field)
                view.setTextColor(context.getColorStateList(R.color.glass_text))
                view.setHintTextColor(GlassTokens.textSecondary)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    view.textCursorDrawable = context.getDrawable(R.drawable.glass_cursor)
                }
            }
            is CheckBox -> {
                view.buttonTintList = null
                view.buttonDrawable = context.getDrawable(R.drawable.glass_check)
                view.backgroundTintList = null
                view.background = GlassMaterial.focusable(context)
                view.setTextColor(GlassMaterial.focusText())
                view.stateListAnimator = null
                view.elevation = 0f
            }
            is Button -> {
                view.backgroundTintList = null
                view.background = GlassMaterial.focusable(context)
                view.setTextColor(GlassMaterial.focusText())
                view.isAllCaps = false
                view.stateListAnimator = null
                view.elevation = 0f
                view.minimumHeight = GlassMaterial.dp(context, 40f)
                view.setPaddingRelative(GlassMaterial.dp(context, 16f), view.paddingTop,
                    GlassMaterial.dp(context, 16f), view.paddingBottom)
            }
            is CheckedTextView -> {
                val list = view.parent as? ListView
                view.checkMarkDrawable = context.getDrawable(if (list?.choiceMode == ListView.CHOICE_MODE_MULTIPLE)
                    R.drawable.glass_check else R.drawable.glass_radio)
                view.checkMarkTintList = null
                view.setTextColor(context.getColorStateList(R.color.glass_row_text))
                view.background = context.getDrawable(R.drawable.glass_row)
            }
            is TextView -> {
                val listRow = view.parent is ListView
                view.setTextColor(if (listRow) context.getColorStateList(R.color.glass_row_text)
                    else if (view.isFocusable || view.isClickable) GlassMaterial.focusText()
                    else ColorStateList.valueOf(GlassTokens.textPrimary))
                if (listRow) view.background = context.getDrawable(R.drawable.glass_row)
            }
            is ListView -> styleList(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) styleView(view.getChildAt(index))
        }
    }

    fun itemsAdapter(context: Context, items: List<CharSequence>, checkedIndex: Int = -1): ListAdapter =
        object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): CharSequence = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? CheckedTextView) ?: CheckedTextView(context).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = GlassMaterial.dp(context, 48f)
                    setPaddingRelative(GlassMaterial.dp(context, 16f), GlassMaterial.dp(context, 12f),
                        GlassMaterial.dp(context, 16f), GlassMaterial.dp(context, 12f))
                    TvTypography.apply(this, 18)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isFocusable = false
                    isClickable = false
                    background = context.getDrawable(R.drawable.glass_row)
                    setTextColor(context.getColorStateList(R.color.glass_row_text))
                    checkMarkDrawable = context.getDrawable(R.drawable.glass_radio)
                }
                row.text = items[position]
                row.isChecked = position == checkedIndex
                row.contentDescription = if (row.isChecked)
                    context.getString(R.string.glass_selected_item, items[position]) else items[position]
                return row
            }
        }
}

fun AlertDialog.Builder.showGlass(): AlertDialog = show().also { GlassDialogs.style(it) }
fun Dialog.showGlass(): Dialog { show(); GlassDialogs.style(this); return this }
