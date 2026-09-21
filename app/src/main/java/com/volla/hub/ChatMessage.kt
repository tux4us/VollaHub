package com.volla.hub

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val items: List<ContentItem> = emptyList(),
    val action: ChatAction? = null
)

enum class ChatAction {
    OPEN_LOCATION,
    TAKE_SCREENSHOT,
    OPEN_STORAGE_ANALYSIS,
    CREATE_REPORT,
    OPEN_WIKI,
    OPEN_FORUM,
    OPEN_BLOG
}
