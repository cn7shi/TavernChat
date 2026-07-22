package com.tavernchat.core.model

import java.util.UUID

/**
 * 消息类型枚举
 */
enum class MessageType {
    TEXT,   // 普通文本消息
    ACK     // 确认签收消息
}

/**
 * 升级版消息实体类
 * 自动生成唯一 UUID，支持 seqId 排序
 */
data class Message(
    val sender: String,                                 // 发送者标识
    val receiver: String,                               // 接收者标识
    val content: String,                                // 消息正文。如果是 ACK 类型，则表示确认收到某条消息的 id
    val id: String = UUID.randomUUID().toString(),      // 消息唯一标识 (默认自动生成 UUID)，用于去重和 ACK 匹配
    val type: MessageType = MessageType.TEXT,           // 消息类型，默认为普通文本
    val seqId: Long = 0L                                // 消息序列号，单调递增，用于接收端乱序重组排序
)
