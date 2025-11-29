package com.hamtaro.toxmessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hamtaro.toxmessenger.R
import com.hamtaro.toxmessenger.service.ToxFriend

class FriendAdapter(
    private var friends: List<ToxFriend>,
    private val onFriendClick: (ToxFriend) -> Unit
) : RecyclerView.Adapter<FriendAdapter.FriendViewHolder>() {
    
    class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val friendName: TextView = itemView.findViewById(R.id.friend_name)
        val friendStatus: TextView = itemView.findViewById(R.id.friend_status)
        val shortToxId: TextView = itemView.findViewById(R.id.short_tox_id)
        val onlineIndicator: View = itemView.findViewById(R.id.online_indicator)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return FriendViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friend = friends[position]
        
        holder.friendName.text = friend.displayName
        holder.friendStatus.text = friend.statusMessage
        
        // Display 6-character Tox ID
        holder.shortToxId.text = friend.publicKey.take(6).uppercase()
        
        // Show online status
        if (friend.isOnline) {
            holder.onlineIndicator.setBackgroundColor(holder.itemView.context.getColor(R.color.accent_green))
        } else {
            holder.onlineIndicator.setBackgroundColor(holder.itemView.context.getColor(R.color.accent_red))
        }
        
        holder.itemView.setOnClickListener {
            onFriendClick(friend)
        }
    }
    
    override fun getItemCount(): Int = friends.size
    
    fun updateFriends(newFriends: List<ToxFriend>) {
        friends = newFriends
        notifyDataSetChanged()
    }
}
