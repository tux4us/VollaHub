package com.volla.hub

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val items: List<ContentItem> = emptyList()
)