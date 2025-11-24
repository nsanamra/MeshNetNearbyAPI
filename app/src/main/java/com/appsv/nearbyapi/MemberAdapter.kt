package com.appsv.nearbyapi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R
import java.text.SimpleDateFormat
import java.util.*

class MemberAdapter(private var members: List<MeshMember>) : RecyclerView.Adapter<MemberAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.memberNameText)
        val idText: TextView = view.findViewById(R.id.memberIdText)
        val timeText: TextView = view.findViewById(R.id.lastSeenText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        holder.nameText.text = if(member.name == "You") "You (Me)" else "User"
        holder.idText.text = member.id

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        holder.timeText.text = "Last seen: ${sdf.format(Date(member.lastSeen))}"
    }

    override fun getItemCount() = members.size

    fun updateList(newMembers: List<MeshMember>) {
        members = newMembers
        notifyDataSetChanged()
    }
}