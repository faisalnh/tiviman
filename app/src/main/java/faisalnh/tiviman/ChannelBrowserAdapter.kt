package faisalnh.tiviman

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView

/** Labels are formatted only for visible/recycled rows, never for the complete catalog. */
class ChannelBrowserAdapter(context: Context, private val rows: List<Channel>, private val tunedId: Int) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = rows[position].id.toLong()
    override fun hasStableIds() = true
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView as? CheckedTextView ?: inflater.inflate(
            android.R.layout.simple_list_item_single_choice, parent, false) as CheckedTextView
        // Rows recycled after the dialog was styled would otherwise keep framework visuals.
        view.background = view.context.getDrawable(R.drawable.glass_row)
        view.setTextColor(view.context.getColorStateList(R.color.glass_row_text))
        view.checkMarkDrawable = view.context.getDrawable(R.drawable.glass_radio)
        view.checkMarkTintList = null
        val channel = rows[position]
        view.text = "${if (channel.id == tunedId) "▶ " else ""}${channel.id + 1}  ${channel.name}"
        return view
    }
}
