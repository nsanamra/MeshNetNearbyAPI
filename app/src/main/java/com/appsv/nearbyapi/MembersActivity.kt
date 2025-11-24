package com.appsv.nearbyapi

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlinechatapp.R

class MembersActivity : AppCompatActivity() {

    private lateinit var membersRecyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var backButton: Button
    private lateinit var adapter: MemberAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_members)

        membersRecyclerView = findViewById(R.id.membersRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        backButton = findViewById(R.id.backButton)

        // Setup Adapter
        adapter = MemberAdapter(emptyList())
        membersRecyclerView.layoutManager = LinearLayoutManager(this)
        membersRecyclerView.adapter = adapter

        // Observe the Singleton Repository
        MeshRepository.activeMembers.observe(this) { members ->
            adapter.updateList(members)
            if (members.isEmpty()) {
                emptyView.text = "No active members found."
                emptyView.visibility = TextView.VISIBLE
            } else {
                emptyView.visibility = TextView.GONE
            }
            supportActionBar?.title = "Active Members (${members.size})"
        }

        backButton.setOnClickListener {
            finish() // Close this activity and go back to Chat
        }
    }
}