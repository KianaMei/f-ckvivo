package com.bytedance.im.core.model

// Test stand-in for the conversation list model whose mark-all step MessageTabMenu and the MCP server call. uid is the
// account the model belongs to.
class ConversationListModel {
    var marked = 0
    var uid = 0L
    var failure: RuntimeException? = null
    fun markAllConversationRead() {
        failure?.let { throw it }
        marked++
    }
}
