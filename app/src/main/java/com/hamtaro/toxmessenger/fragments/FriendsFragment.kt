package com.hamtaro.toxmessenger.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hamtaro.toxmessenger.R
import com.hamtaro.toxmessenger.adapters.FriendAdapter
import com.hamtaro.toxmessenger.service.ToxFriend

class FriendsFragment : Fragment() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var addFriendButton: FloatingActionButton
    private lateinit var friendAdapter: FriendAdapter
    private var friends = listOf<ToxFriend>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_friends, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.friends_recycler_view)
        addFriendButton = view.findViewById(R.id.add_friend_button)
        
        setupRecyclerView()
        setupListeners()
    }
    
    private fun setupRecyclerView() {
        friendAdapter = FriendAdapter(friends) { friend ->
            // Handle friend click - open chat
            Toast.makeText(context, "Chat with ${friend.displayName}", Toast.LENGTH_SHORT).show()
        }
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = friendAdapter
        }
    }
    
    private fun setupListeners() {
        addFriendButton.setOnClickListener {
            showAddFriendDialog()
        }
    }
    
    private fun showAddFriendDialog() {
        // This would show a dialog to add a friend by Tox ID
        // For now, show a simple toast
        Toast.makeText(context, "Add friend dialog - Enter 6-char Tox ID", Toast.LENGTH_SHORT).show()
    }
    
    fun updateFriends(newFriends: List<ToxFriend>) {
        friends = newFriends
        friendAdapter.updateFriends(newFriends)
    }
}
