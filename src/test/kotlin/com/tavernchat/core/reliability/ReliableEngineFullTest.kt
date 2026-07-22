package com.tavernchat.core.reliability

import com.tavernchat.core.channel.InMemoryMessageChannel
import com.tavernchat.core.model.Message
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReliableEngineFullTest {

    /**
     * 1. 验证去重功能：发送 3 条相同 ID 的重复消息，Bob 界面只展示 1 次
     */
    @Test
    fun testDeduplication() = runTest {
        val channel = InMemoryMessageChannel()
        val receivedMessages = mutableListOf<Message>()

        val bobReceiver = ReliableReceiver("Bob", channel) { msg ->
            receivedMessages.add(msg)
        }

        val aliceSender = ReliableSender(
            senderId = "Alice",
            channel = channel,
            retryStrategy = FixedDelayRetryStrategy(100, 3),
            scope = this
        )

        val dupMsg = Message(sender = "Alice", receiver = "Bob", content = "重复测试消息", id = "dup_id_999", seqId = 1L)

        // 故意连续发送 3 条一模一样的消息
        aliceSender.sendReliable(dupMsg)
        aliceSender.sendReliable(dupMsg)
        aliceSender.sendReliable(dupMsg)

        advanceUntilIdle()

        // 验证 Bob 界面只收到了 1 条，其余重复消息全被成功拦截！
        assertEquals(1, receivedMessages.size, "重复消息应该被去重器拦截，界面只能展示 1 次")
        assertEquals("dup_id_999", receivedMessages[0].id)
    }

    /**
     * 2. 验证乱序重组功能：故意颠倒顺序发送 (3 -> 2 -> 1)，Bob 界面最终按 (1 -> 2 -> 3) 顺序输出
     */
    @Test
    fun testReordering() = runTest {
        val channel = InMemoryMessageChannel()
        val receivedContents = mutableListOf<String>()

        val bobReceiver = ReliableReceiver("Bob", channel) { msg ->
            receivedContents.add(msg.content)
        }

        val msg1 = Message(sender = "Alice", receiver = "Bob", content = "我是第1句", id = "id_1", seqId = 1L)
        val msg2 = Message(sender = "Alice", receiver = "Bob", content = "我是第2句", id = "id_2", seqId = 2L)
        val msg3 = Message(sender = "Alice", receiver = "Bob", content = "我是第3句", id = "id_3", seqId = 3L)

        // 故意倒序投递：先发 3，再发 2，最后发 1
        channel.send(msg3)
        channel.send(msg2)
        channel.send(msg1)

        advanceUntilIdle()

        // 验证 Bob 界面收到的顺序完美修正为 1 -> 2 -> 3
        assertEquals(3, receivedContents.size)
        assertEquals("我是第1句", receivedContents[0])
        assertEquals("我是第2句", receivedContents[1])
        assertEquals("我是第3句", receivedContents[2])
    }
}
