package com.hamtaro.toxmessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hamtaro.toxmessenger.R
import com.hamtaro.toxmessenger.service.ToxMessage
import com.hamtaro.toxmessenger.utils.EmojiProcessor
import com.hamtaro.toxmessenger.utils.ThemeManager

class ChatAdapter(
    private var messages: List<ToxMessage>,
    private val onMessageClick: (ToxMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.message_text)
        val senderText: TextView = itemView.findViewById(R.id.sender_text)
        val messageContainer: View = itemView.findViewById(R.id.message_container)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        
        // Process Japanese-style emojis
        val processedText = EmojiProcessor.processEmojis(message.content)
        holder.messageText.text = processedText
        holder.senderText.text = message.sender
        
        // Apply theme colors
        val colors = ThemeManager.getThemeColors()
        if (message.isIncoming) {
            holder.messageContainer.setBackgroundResource(R.drawable.bg_message_incoming)
            holder.messageText.setTextColor(holder.itemView.context.getColor(R.color.on_surface_dark))
        } else {
            holder.messageContainer.setBackgroundResource(R.drawable.bg_message_outgoing)
            holder.messageText.setTextColor(holder.itemView.context.getColor(R.color.white))
        }
        
        // No timestamp display as requested
        
        holder.itemView.setOnClickListener {
            onMessageClick(message)
        }
    }
    
    override fun getItemCount(): Int = messages.size
    
    fun updateMessages(newMessages: List<ToxMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}
