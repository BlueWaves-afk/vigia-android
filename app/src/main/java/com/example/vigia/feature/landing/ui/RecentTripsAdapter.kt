package com.example.vigia.feature.landing.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.R
import com.example.vigia.feature.landing.model.RecentTrip

class RecentTripsAdapter(
    private val onClick: (RecentTrip) -> Unit
) : ListAdapter<RecentTrip, RecentTripsAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<RecentTrip>() {
        override fun areItemsTheSame(oldItem: RecentTrip, newItem: RecentTrip) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RecentTrip, newItem: RecentTrip) = oldItem == newItem
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtLabel: TextView = itemView.findViewById(R.id.txtLabel)
        private val txtDestination: TextView = itemView.findViewById(R.id.txtDestination)

        fun bind(item: RecentTrip) {
            txtLabel.text = item.label
            txtDestination.text = item.destination
            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_trip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}