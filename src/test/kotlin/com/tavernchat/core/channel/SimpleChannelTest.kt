package com.tavernchat.core.channel

import com.tavernchat.core.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimpleChannelTest {

    /**
     * 单向路由测试：验证 Bob 能够收到 Alice 的私信
     */
    @Test
    fun testMessageRouting() {
        val channel = InMemoryMessageChannel()
        var receivedMessage: Message? = null

        // 1. Bob 注册门铃
        channel.registerListener("Bob", object : MessageListener {
            override fun onMessageReceived(message: Message) {
                println("[测试] Bob 的门铃响了！收到内容: ${message.content}")
                receivedMessage = message
            }
        })

        // 2. Alice 发信
        val msg = Message(sender = "Alice", receiver = "Bob", content = "Hello, Bob!")
        channel.send(msg)

        // 3. 验证收信结果
        assertNotNull(receivedMessage, "Bob 应该收到消息")
        assertEquals("Hello, Bob!", receivedMessage?.content)
    }

    /**
     * 双向通信测试：模拟 Alice 发消息给 AI 助手，AI 助手自动回复，Alice 收到回复的闭环
     */
    @Test
    fun testBidirectionalChat() {
        val channel = InMemoryMessageChannel()
        var aliceReceivedReply: Message? = null

        // 1. Alice 注册门铃：用来接收 AI 机器人的回信
        channel.registerListener("Alice", object : MessageListener {
            override fun onMessageReceived(message: Message) {
                println("[Alice 手机响了] 收到来自 ${message.sender} 的回信: \"${message.content}\"")
                aliceReceivedReply = message
            }
        })

        // 2. AI_Bot 注册门铃：收到消息后，自动编写回信并发送
        channel.registerListener("AI_Bot", object : MessageListener {
            override fun onMessageReceived(message: Message) {
                println("[AI 服务器收到消息] 收到 ${message.sender} 的请求: \"${message.content}\"")
                
                // 收到消息后，自动构建一封回复信，投递回给发送者（也就是 Alice）
                val replyMsg = Message(
                    sender = "AI_Bot",
                    receiver = message.sender, // 谁发来的，就回给谁
                    content = "你好，我是 AI 助手！我收到了你的消息：'${message.content}'"
                )
                
                // 再次通过通道发送
                channel.send(replyMsg)
            }
        })

        // 3. Alice 发送第一条消息，启动对话
        val startMsg = Message(sender = "Alice", receiver = "AI_Bot", content = "今天天气怎么样？")
        println("\n--- [测试开始] Alice 按下了发送键 ---")
        channel.send(startMsg)
        println("--- [测试结束] --- \n")

        // 4. 验证 Alice 确实收到了 AI 机器人的自动回复
        assertNotNull(aliceReceivedReply, "Alice 应该收到 AI 的回信")
        assertEquals("AI_Bot", aliceReceivedReply?.sender)
        assertEquals("你好，我是 AI 助手！我收到了你的消息：'今天天气怎么样？'", aliceReceivedReply?.content)
    }

    /**
     * 不稳定通道测试：验证 100% 丢包时，Bob 确实收不到任何消息
     */
    @Test
    fun testUnreliableChannelLoss() {
        // 1. 初始化丢包率为 100% 的不稳定通道
        val channel = UnreliableMessageChannel(lossRate = 1.0)
        var receivedMessage: Message? = null

        // 2. 注册 Bob 的门铃
        channel.registerListener("Bob", object : MessageListener {
            override fun onMessageReceived(message: Message) {
                receivedMessage = message
            }
        })

        // 3. Alice 发送一条 ID 为 "msg_001" 的消息
        val msg = Message(
            sender = "Alice", 
            receiver = "Bob", 
            content = "Hello, Bob!", 
            id = "msg_001"
        )
        channel.send(msg)

        // 4. 自动化断言：因为 100% 丢包，Bob 应该保持 null，即没收到任何消息
        assertNull(receivedMessage, "由于 100% 丢包，Bob 绝对不应该收到这封信")
    }
}
