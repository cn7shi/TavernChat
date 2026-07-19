package com.tavernchat.core.model

/**
 * 消息类型枚举
 */
enum class MessageType {
    TEXT,   // 普通文本消息
    ACK     // 确认签收消息
}

/**
 * 升级版消息实体类
 * 增加了 id 和 type，并赋予默认值，完美兼容老代码
 */
data class Message(
    val sender: String,                        // 发送者标识
    val receiver: String,                      // 接收者标识
    val content: String,                       // 消息正文。如果是 ACK 类型，则表示确认收到某条消息的 id
    val id: String = "",                       // 消息唯一标识，默认为空，发 ACK 时开始启用
    val type: MessageType = MessageType.TEXT   // 消息类型，默认为普通文本
)
