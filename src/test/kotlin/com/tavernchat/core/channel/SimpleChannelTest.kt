package com.tavernchat.core.channel

import com.tavernchat.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SimpleChannelTest {

    @Test
    fun testMessageRouting() {
        val channel = InMemoryMessageChannel()
        var receivedMessage: Message? = null

        // 1. Bob 注册门铃（此时只定义动作，不执行）
        channel.registerListener("Bob", object : MessageListener {
            override fun onMessageReceived(message: Message) {
                println("[测试] Bob 的门铃响了！收到内容: ${message.content}")
                receivedMessage = message
            }
        })

        // 2. Alice 发信 -> 内部会瞬间触发按门铃的动作
        val msg = Message(sender = "Alice", receiver = "Bob", content = "Hello, Bob!")
        channel.send(msg)

        // 3. 断言验证
        assertNotNull(receivedMessage, "Bob 应该收到消息")
        assertEquals("Hello, Bob!", receivedMessage?.content)
    }
}
