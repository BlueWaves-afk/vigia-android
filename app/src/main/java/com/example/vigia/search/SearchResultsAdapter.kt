package com.example.vigia.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat // ADD THIS IMPORT
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.R // Ensure this matches your package

class SearchResultsAdapter(
    private val onClick: (PlaceEntity) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

    private var items = listOf<PlaceEntity>()

    fun updateList(newItems: List<PlaceEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.text1.text = item.name
        holder.text2.text = if (item.isCritical) "OFFLINE SAFE HAVEN • ${item.address}" else item.address

        val context = holder.itemView.context

        // FIX: Use theme-aware colors instead of hardcoded White
        if (item.isCritical) {
            holder.text1.setTextColor(ContextCompat.getColor(context, R.color.brandSafe)) // Green
        } else {
            // This will be Black in Light Mode and White in Dark Mode
            holder.text1.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
        }

        // Also set the secondary text color
        holder.text2.setTextColor(ContextCompat.getColor(context, R.color.textSecondary))

        holder.itemView.setOnClickListener { onClick(item) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
        val text2: TextView = view.findViewById(android.R.id.text2)
    }

    override fun getItemCount() = items.size
}