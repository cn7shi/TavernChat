package com.tavernchat.core.reliability

import com.tavernchat.core.channel.UnreliableMessageChannel
import com.tavernchat.core.model.Message
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReliableMessagingTest {

    @Test
    fun testReliableSendWithPacketLoss() = runTest {
        // 1. 设置 50% 高丢包率的弱网通道
        val channel = UnreliableMessageChannel(lossRate = 0.5)
        var bobReceivedContent: String? = null

        // 2. 初始化 Bob 的接收器（构造函数内部会自动将门铃挂载到通道）
        val bobReceiver = ReliableReceiver("Bob", channel) { msg ->
            println("[Bob 最终成果] 🎉 成功在界面展示消息: \"${msg.content}\"")
            bobReceivedContent = msg.content
        }

        // 3. 初始化 Alice 的发送保镖（每隔 100ms 重试一次，最多重试 5 次）
        val aliceSender = ReliableSender(
            senderId = "Alice",
            channel = channel,
            retryStrategy = FixedDelayRetryStrategy(delayMs = 100, maxRetries = 5),
            scope = this
        )

        // 4. Alice 发送消息
        val message = Message(
            sender = "Alice",
            receiver = "Bob",
            content = "这是一条在50%丢包下必须送达的硬核消息！",
            id = "msg_1001"
        )

        aliceSender.sendReliable(message)

        // 5. 核心：快进测试虚拟时间，让后台的协程超时重试闹钟全量响应
        testScheduler.advanceUntilIdle()

        // 6. 断言检查
        assertNotNull(bobReceivedContent, "Bob 最终应该克服丢包收到消息")
        assertEquals("这是一条在50%丢包下必须送达的硬核消息！", bobReceivedContent)
    }
}
