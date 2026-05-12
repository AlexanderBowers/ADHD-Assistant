package com.example.adhdassistant.ui.location

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.R

class LocationResultAdapter(
    private val onItemClick: (PickedLocation) -> Unit
) : ListAdapter<PickedLocation, LocationResultAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_location_result, parent, false)
    ) {
        private val tvName = itemView.findViewById<TextView>(R.id.tvAddressName)
        private val tvFull = itemView.findViewById<TextView>(R.id.tvAddressFull)

        fun bind(item: PickedLocation) {
            tvName.text = item.name
            tvFull.text = item.fullAddress
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PickedLocation>() {
            override fun areItemsTheSame(old: PickedLocation, new: PickedLocation) =
                old.lat == new.lat && old.lng == new.lng
            override fun areContentsTheSame(old: PickedLocation, new: PickedLocation) = old == new
        }
    }
}
