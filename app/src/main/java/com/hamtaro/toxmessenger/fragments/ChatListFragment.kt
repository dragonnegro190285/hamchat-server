package com.hamtaro.toxmessenger.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hamtaro.toxmessenger.MainActivity
import com.hamtaro.toxmessenger.R
import com.hamtaro.toxmessenger.adapters.ChatAdapter
import com.hamtaro.toxmessenger.service.ToxMessage

class ChatListFragment : Fragment() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private var messages = listOf<ToxMessage>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.chat_recycler_view)
        setupRecyclerView()
        
        // Observe messages from service
        // This would be connected to the ToxService in a real implementation
    }
    
    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages) { message ->
            // Check for secret phrase
            (activity as? MainActivity)?.checkSecretPhrase(message.content)
        }
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = chatAdapter
        }
    }
    
    fun updateMessages(newMessages: List<ToxMessage>) {
        messages = newMessages
        chatAdapter.updateMessages(newMessages)
    }
}
