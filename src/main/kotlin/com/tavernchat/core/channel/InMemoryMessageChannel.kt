package com.tavernchat.core.channel

import com.tavernchat.core.model.Message

/**
 * 具备“精准路由”功能的内存消息通道
 */
class InMemoryMessageChannel : MessageChannel {
    
    // 使用 Map 存储：[用户ID -> 该用户的门铃监听器]
    private val listeners = mutableMapOf<String, MessageListener>()

    override fun send(message: Message) {
        println("[通道] 收到投递请求: ${message.sender} 发给 ${message.receiver} -> \"${message.content}\"")
        
        // 找出指定接收者的门铃进行回调，实现定向投递
        val targetListener = listeners[message.receiver]
        if (targetListener != null) {
            targetListener.onMessageReceived(message)
        } else {
            // 如果对方没有在线（没注册门铃），提示离线
            println("[通道] 投递失败！接收者 \"${message.receiver}\" 当前不在线或未注册监听。")
        }
    }

    override fun registerListener(userId: String, listener: MessageListener) {
        // 将用户与他的门铃绑定
        listeners[userId] = listener
        println("[通道] 用户 \"$userId\" 的门铃已成功挂载。")
    }
}
