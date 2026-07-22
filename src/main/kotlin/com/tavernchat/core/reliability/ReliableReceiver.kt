package com.tavernchat.core.reliability

import com.tavernchat.core.channel.MessageChannel
import com.tavernchat.core.channel.MessageListener
import com.tavernchat.core.model.Message
import com.tavernchat.core.model.MessageType

/**
 * 可靠消息接收器 (完全体)
 * 整合了 ACK 自动回复、LRU 动态去重、小顶堆乱序重组功能。
 *
 * @param userId 接收器所属的用户 ID (如 "Bob")
 * @param channel 底层的物理通信通道
 * @param deduplicator 幂等去重器 (默认使用 ReliabilityConfig 容量)
 * @param reorderer 乱序重组队列 (默认使用 ReliabilityConfig 容量)
 * @param onTextReceived 当真正收到有序文本消息时的业务回调 (交付界面)
 */
class ReliableReceiver(
    val userId: String,
    private val channel: MessageChannel,
    private val deduplicator: Deduplicator = Deduplicator(),
    private val reorderer: SequenceReorderer = SequenceReorderer(),
    private val onTextReceived: (Message) -> Unit
) : MessageListener {

    init {
        // 构造时自动挂载门铃到通道上
        channel.registerListener(userId, this)
    }

    override fun onMessageReceived(message: Message) {
        // 安全防火墙：非发给我的消息直接忽略
        if (message.receiver != userId) return

        when (message.type) {
            MessageType.TEXT -> {
                // 1. 幂等去重检查
                if (deduplicator.isDuplicate(message.id)) {
                    println("[$userId 接收端] ⚠️ 检测到重复消息 (ID: ${message.id})，忽略渲染，但补发 ACK！")
                    // 核心细节：依然补发 ACK，防止发送端因为丢失 ACK 而盲目无限重发
                    sendAck(toUser = message.sender, ackMessageId = message.id)
                    return
                }

                println("[$userId 接收端] 收到原始消息: \"${message.content}\" (Seq: ${message.seqId}, ID: ${message.id})")

                // 2. 自动回复 ACK 确认包
                sendAck(toUser = message.sender, ackMessageId = message.id)

                // 3. 乱序重组处理
                val orderedMessages = reorderer.processMessage(message)

                // 4. 将排序重组好的连续消息按顺序交付给界面展示
                orderedMessages.forEach { orderedMsg ->
                    println("[$userId 接收端] 🎉 顺序开闸交付界面展示: \"${orderedMsg.content}\" (Seq: ${orderedMsg.seqId})")
                    onTextReceived(orderedMsg)
                }
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
