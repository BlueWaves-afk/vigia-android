package com.example.vigia.feature.splash.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.feature.splash.model.FileStatus
import com.example.vigia.feature.splash.model.ModelFile

class FileStatusAdapter(
    private var items: List<ModelFile>
) : RecyclerView.Adapter<FileStatusAdapter.VH>() {

    fun submitList(newItems: List<ModelFile>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(android.R.id.text1)
        val txtState: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        holder.txtName.text = file.fileName
        holder.txtName.textSize = 12f
        holder.txtName.setTextColor(Color.parseColor("#E0E0E0"))

        when (file.status) {
            FileStatus.PENDING -> {
                holder.txtState.text = "Pending"
                holder.txtState.setTextColor(Color.GRAY)
            }
            FileStatus.DOWNLOADING -> {
                holder.txtState.text = "Downloading..."
                holder.txtState.setTextColor(Color.parseColor("#4E89FF"))
            }
            FileStatus.COMPLETED -> {
                holder.txtState.text = "✓ Ready"
                holder.txtState.setTextColor(Color.parseColor("#4CAF50"))
            }
            FileStatus.ERROR -> {
                holder.txtState.text = "✗ Failed"
                holder.txtState.setTextColor(Color.RED)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}