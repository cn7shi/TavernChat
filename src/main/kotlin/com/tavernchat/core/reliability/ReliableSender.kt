package com.tavernchat.core.reliability

import com.tavernchat.core.channel.MessageChannel
import com.tavernchat.core.channel.MessageListener
import com.tavernchat.core.model.Message
import com.tavernchat.core.model.MessageType
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 可靠消息发送器
 * 负责带有超时重发和 ACK 自动取消机制的消息发送。
 *
 * @param senderId 发送者用户 ID (例如 "Alice")
 * @param channel 物理通信通道
 * @param retryStrategy 重试延迟与次数策略
 * @param scope 协程作用域，用于启动超时重发任务
 */
class ReliableSender(
    val senderId: String,
    private val channel: MessageChannel,
    private val retryStrategy: RetryStrategy,
    private val scope: CoroutineScope
) : MessageListener {

    private val pendingAcks = ConcurrentHashMap<String, Job>()

    init {
        channel.registerListener(senderId, this)
    }

    /**
     * 可靠发送一条消息
     */
    fun sendReliable(message: Message) {
        println("[$senderId 发送端] 🚀 发送消息 (ID: ${message.id}): \"${message.content}\"")

        // 🚀 核心修复【消除首包 ACK 竞态】：先创建并注册协程闹钟 Job 到 pendingAcks 账本中，然后再执行物理发送！
        val timerJob = scope.launch {
            var retryCount = 0

            while (isActive) {
                val delayMs = retryStrategy.nextDelay(retryCount)

                if (delayMs < 0) {
                    println("[$senderId 发送端] ❌ 消息 (ID: ${message.id}) 达到最大重发次数，宣告发送失败！")
                    pendingAcks.remove(message.id)
                    break
                }

                delay(delayMs)

                retryCount++
                println("[$senderId 发送端] ⚠️ 超时未收到 ACK，第 $retryCount 次重发消息 (ID: ${message.id})...")
                channel.send(message)
            }
        }

        // 1. 先存入等待账本！
        pendingAcks[message.id] = timerJob

        // 2. 后进行物理发送！避免同步通道下 ACK 瞬间飞回时 pendingAcks 尚未写入的竞态 Bug
        channel.send(message)
    }

    override fun onMessageReceived(message: Message) {
        if (message.receiver == senderId && message.type == MessageType.ACK) {
            val ackedMessageId = message.content
            println("[$senderId 发送端] ✅ 成功收到来自 ${message.sender} 的 ACK 确认回执 (针对消息 ID: $ackedMessageId)")

            val job = pendingAcks.remove(ackedMessageId)
            job?.cancel()
        }
    }
}
