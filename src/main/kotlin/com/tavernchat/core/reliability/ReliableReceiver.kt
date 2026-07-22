package com.tavernchat.core.reliability

import com.tavernchat.core.channel.MessageChannel
import com.tavernchat.core.channel.MessageListener
import com.tavernchat.core.model.Message
import com.tavernchat.core.model.MessageType
import java.util.concurrent.ConcurrentHashMap

/**
 * 可靠消息接收器 (完全体)
 * 整合了 ACK 自动回复、LRU 动态去重、小顶堆乱序重组功能。
 * 支持按发送者 (sender) 隔离独立维护有序序列流。
 */
class ReliableReceiver(
    val userId: String,
    private val channel: MessageChannel,
    private val deduplicator: Deduplicator = Deduplicator(),
    private val onTextReceived: (Message) -> Unit
) : MessageListener {

    // 🚀 核心修复：按 sender 隔离维护各自的重组队列，防止跨发送者序列号错乱
    private val reordererMap = ConcurrentHashMap<String, SequenceReorderer>()

    init {
        channel.registerListener(userId, this)
    }

    override fun onMessageReceived(message: Message) {
        if (message.receiver != userId) return

        when (message.type) {
            MessageType.TEXT -> {
                // 1. 幂等去重检查
                if (deduplicator.isDuplicate(message.id)) {
                    println("[$userId 接收端] ⚠️ 检测到重复消息 (ID: ${message.id})，忽略渲染，但补发 ACK！")
                    sendAck(toUser = message.sender, ackMessageId = message.id)
                    return
                }

                println("[$userId 接收端] 收到原始消息: \"${message.content}\" (Seq: ${message.seqId}, ID: ${message.id})")

                // 2. 自动回复 ACK
                sendAck(toUser = message.sender, ackMessageId = message.id)

                // 3. 动态获取/创建该发送者专属的重组队列
                val senderReorderer = reordererMap.computeIfAbsent(message.sender) {
                    SequenceReorderer()
                }

                // 4. 按发送者隔离乱序重组
                val orderedMessages = senderReorderer.processMessage(message)

                // 5. 按顺序交付界面展示
                orderedMessages.forEach { orderedMsg ->
                    println("[$userId 接收端] 🎉 [来自于 ${message.sender}] 顺序开闸交付界面展示: \"${orderedMsg.content}\" (Seq: ${orderedMsg.seqId})")
                    onTextReceived(orderedMsg)
                }
            }
            MessageType.ACK -> {
                println("[$userId 接收端] 收到 ACK 消息，本接收端忽略。")
            }
        }
    }

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
