package com.tavernchat.core.channel

import com.tavernchat.core.model.Message

/**
 * 消息接收监听器
 * 任何想要接收通道消息的组件都可以实现这个接口。
 */
interface MessageListener {
    fun onMessageReceived(message: Message)
}

/**
 * 消息通道接口
 * 定义了消息发送和精准路由接收的契约。
 */
interface MessageChannel {
    /**
     * 发送消息
     */
    fun send(message: Message)

    /**
     * 注册消息监听器。需要指定用户 ID，以便通道进行定向推送。
     */
    fun registerListener(userId: String, listener: MessageListener)
}
