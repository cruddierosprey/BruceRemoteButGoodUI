package io.bruceremote.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import io.bruceremote.app.R
import io.bruceremote.app.protocol.BruceMenuOption

class MenuOptionAdapter(
    context: Context,
) : BaseAdapter() {
    private data class ViewHolder(
        val root: LinearLayout,
        val number: TextView,
        val label: TextView,
        val activeBadge: TextView,
    )

    private val inflater = LayoutInflater.from(context)
    private var options: List<BruceMenuOption> = emptyList()
    private var activeIndex: Int = -1

    fun submitList(newOptions: List<BruceMenuOption>, newActiveIndex: Int) {
        options = newOptions
        activeIndex = newActiveIndex
        notifyDataSetChanged()
    }

    override fun getCount(): Int = options.size

    override fun getItem(position: Int): BruceMenuOption = options[position]

    override fun getItemId(position: Int): Long = options[position].number.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: ViewHolder
        if (convertView == null) {
            view = inflater.inflate(R.layout.item_menu_option, parent, false)
            holder = ViewHolder(
                root = view.findViewById(R.id.optionRoot),
                number = view.findViewById(R.id.optionNumber),
                label = view.findViewById(R.id.optionLabel),
                activeBadge = view.findViewById(R.id.activeBadge),
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        val option = getItem(position)
        val active = position == activeIndex
        holder.number.text = option.number.toString()
        holder.label.text = option.label
        holder.activeBadge.visibility = if (active) View.VISIBLE else View.GONE
        holder.root.setBackgroundResource(
            if (active) R.drawable.active_option_background
            else R.drawable.inactive_option_background,
        )
        holder.root.contentDescription = buildString {
            append("Option ${option.number}: ${option.label}")
            if (active) append(", active")
        }
        return view
    }
}
