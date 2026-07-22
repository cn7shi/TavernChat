package com.tavernchat.core.reliability

import com.tavernchat.core.channel.MessageChannel
import com.tavernchat.core.channel.MessageListener
import com.tavernchat.core.model.Message
import com.tavernchat.core.model.MessageType

/**
 * 可靠消息接收器
 * 负责监听通道消息，并在收到普通文本消息时，自动回复 ACK 确认包给发送方。
 *
 * @param userId 接收器所属的用户 ID (如 "Bob")
 * @param channel 底层的物理通信通道，用于发送 ACK 回执
 * @param onTextReceived 当真正收到普通文本消息时的业务回调（比如交给 UI 渲染）
 */
class ReliableReceiver(
    val userId: String,
    private val channel: MessageChannel,
    private val onTextReceived: (Message) -> Unit
) : MessageListener {

    init {
        // 挂载门铃：让通道把发给 userId 的消息路由给本接收器
        channel.registerListener(userId, this)
    }

    override fun onMessageReceived(message: Message) {
        // 安全防火墙：如果收到的消息不是发给我的，直接忽略
        if (message.receiver != userId) return

        when (message.type) {
            MessageType.TEXT -> {
                println("[$userId 接收端] 收到来自 ${message.sender} 的文本消息: \"${message.content}\" (ID: ${message.id})")
                
                // 1. 回调业务层，让聊天界面显示消息
                onTextReceived(message)

                // 2. 核心 ACK 机制：立刻向发送者回复一个 ACK 确认数据包
                sendAck(toUser = message.sender, ackMessageId = message.id)
            }
            MessageType.ACK -> {
                println("[$userId 接收端] 收到 ACK 消息，本接收端忽略。")
            }
        }
    }

    /**
     * 发送 ACK 确认包
     */
    private fun sendAck(toUser: String, ackMessageId: String) {
        val ackMessage = Message(
            sender = userId,
            receiver = toUser,
            content = ackMessageId,
            id = "ack_${ackMessageId}",
            type = MessageType.ACK
        )
        println("[$userId 接收端] 🚀 自动回复 ACK 确认信给 $toUser: (针对 ID: $ackMessageId)")
        channel.send(ackMessage)
    }
}
