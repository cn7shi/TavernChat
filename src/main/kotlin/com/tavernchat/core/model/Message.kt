package com.tavernchat.core.model

/**
 * 极简消息实体类
 * 包含单条消息最基础的发送人、接收人和内容
 */
data class Message(
    val sender: String,     // 发送者标识 (例如: "Alice")
    val receiver: String,   // 接收者标识 (例如: "Bob")
    val content: String     // 消息正文 (例如: "Hello!")
)
