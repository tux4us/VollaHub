package com.volla.hub

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ChatAdapter(
    private val onContentClick: (ContentItem) -> Unit,
    private val onActionClick: (ChatAction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_BOT = 2
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_USER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_bot, parent, false)
            BotViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserViewHolder) {
            holder.tvMessage.text = message.text
        } else if (holder is BotViewHolder) {
            holder.tvBotMessage.text = message.text
            
            // Content Results
            if (message.items.isNotEmpty()) {
                holder.rvResults.visibility = View.VISIBLE
                holder.rvResults.layoutManager = LinearLayoutManager(holder.itemView.context)
                val adapter = ContentAdapter(onContentClick)
                holder.rvResults.adapter = adapter
                adapter.submitList(message.items)
            } else {
                holder.rvResults.visibility = View.GONE
            }

            // Action Button
            if (message.action != null) {
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.text = when(message.action) {
                    ChatAction.OPEN_LOCATION -> "Ortung öffnen"
                    ChatAction.TAKE_SCREENSHOT -> "Screenshot machen"
                    ChatAction.OPEN_STORAGE_ANALYSIS -> "Speicher-Analyse"
                    ChatAction.CREATE_REPORT -> "Geräte-Report erstellen"
                    ChatAction.OPEN_WIKI -> "Wiki öffnen"
                    ChatAction.OPEN_FORUM -> "Forum öffnen"
                    ChatAction.OPEN_BLOG -> "Blog lesen"
                }
                holder.btnAction.setOnClickListener { onActionClick(message.action) }
            } else {
                holder.btnAction.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    class BotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBotMessage: TextView = view.findViewById(R.id.tvBotMessage)
        val rvResults: RecyclerView = view.findViewById(R.id.rvResults)
        val btnAction: MaterialButton = view.findViewById(R.id.btnAction)
    }
}
