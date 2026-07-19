package com.tavernchat.core.channel

import com.tavernchat.core.model.Message
import kotlin.random.Random

/**
 * 模拟弱网（丢包）的消息通道
 * @param lossRate 丢包率，取值范围为 0.0 到 1.0。例如 0.3 表示有 30% 的概率发生丢包。
 */
class UnreliableMessageChannel(private val lossRate: Double = 0.3) : MessageChannel {

    // 存储用户ID和对应的门铃监听器
    private val listeners = mutableMapOf<String, MessageListener>()

    override fun send(message: Message) {
        println("[不稳定通道] 收到投递请求: ${message.sender} -> ${message.receiver} : \"${message.content}\" (ID: ${message.id})")

        // 核心丢包逻辑：产生随机数判定是否丢包
        if (Random.nextDouble() < lossRate) {
            println("[不稳定通道] ❌ [丢包模拟] !!! 发生网络丢包，消息 (ID: ${message.id}) 丢失在虚空中 !!!")
            return // 直接返回，代表数据包在网络中丢失，Bob 根本收不到
        }

        // 未丢包时，正常路由消息
        val targetListener = listeners[message.receiver]
        if (targetListener != null) {
            targetListener.onMessageReceived(message)
        } else {
            println("[不稳定通道] 投递失败！接收者 \"${message.receiver}\" 不在线。")
        }
    }

    override fun registerListener(userId: String, listener: MessageListener) {
        listeners[userId] = listener
        println("[不稳定通道] 用户 \"$userId\" 的门铃挂载成功。")
    }
}
