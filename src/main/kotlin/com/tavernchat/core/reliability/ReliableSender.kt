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

    // 用并发安全的 Map 存储：[消息ID -> 该消息对应的超时重传协程 Job]
    private val pendingAcks = ConcurrentHashMap<String, Job>()

    init {
        // 注册门铃：监听发还给 Alice 的 ACK 确认包
        channel.registerListener(senderId, this)
    }

    /**
     * 可靠发送一条消息
     */
    fun sendReliable(message: Message) {
        println("[$senderId 发送端] 🚀 发送消息 (ID: ${message.id}): \"${message.content}\"")
        
        // 1. 第一次尝试直接发送
        channel.send(message)

        // 2. 启动一个协程，在后台监控 ACK 并执行超时重发
        val timerJob = scope.launch {
            var retryCount = 0
            
            while (isActive) {
                // 问计算器：下一次重发需要等多久？
                val delayMs = retryStrategy.nextDelay(retryCount)
                
                if (delayMs < 0) {
                    println("[$senderId 发送端] ❌ 消息 (ID: ${message.id}) 达到最大重试次数，宣告发送失败！")
                    pendingAcks.remove(message.id)
                    break
                }

                // 核心协程挂起：不卡顿当前线程，到点醒来
                delay(delayMs)

                // 醒来时如果协程未被 cancel，说明还没收到 ACK，立刻重发！
                retryCount++
                println("[$senderId 发送端] ⚠️ 超时未收到 ACK，第 $retryCount 次重发消息 (ID: ${message.id})...")
                channel.send(message)
            }
        }

        // 3. 将该消息的协程任务放入等待表
        pendingAcks[message.id] = timerJob
    }

    /**
     * 门铃被按响：处理收到 ACK 的逻辑
     */
    override fun onMessageReceived(message: Message) {
        // 只处理发给我的 ACK 消息
        if (message.receiver == senderId && message.type == MessageType.ACK) {
            val ackedMessageId = message.content
            println("[$senderId 发送端] ✅ 成功收到来自 ${message.sender} 的 ACK 确认回执 (针对消息 ID: $ackedMessageId)")

            // 从等待表中移除该消息，并直接取消（Cancel）它的重传协程闹钟！
            val job = pendingAcks.remove(ackedMessageId)
            job?.cancel() // 核心：切断闹钟，停止后续重发
        }
    }
}
