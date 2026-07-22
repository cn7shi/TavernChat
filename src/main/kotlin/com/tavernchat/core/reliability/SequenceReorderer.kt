package com.tavernchat.core.reliability

import com.tavernchat.core.model.Message
import java.util.PriorityQueue

/**
 * 乱序重组队列
 * 专门用于在接收端将网络乱序到达的消息重新排成连续有序的序列，再交付给上层渲染。
 *
 * 💡【架构设计与防死锁说明】：
 * 1. 为什么选择 PriorityQueue (小顶堆)？
 *    小顶堆在插入元素时能自动保持堆顶元素为最小 seqId (插入复杂度 O(log N))。
 * 2. 防死锁容量限制 (maxBufferSize)：
 *    若某条前置消息永久丢包，缓冲区不能无限死等。当积压数量超过 maxBufferSize 时，
 *    强制跳过缺失号，放行现存最小号消息，防止内存死锁。
 *
 * @param initialExpectedSeqId 初始期望收到的序列号
 * @param maxBufferSize 重组缓冲区的最大积压容量上限
 */
class SequenceReorderer(
    initialExpectedSeqId: Long = ReliabilityConfig.DEFAULT_START_SEQ_ID,
    private val maxBufferSize: Int = ReliabilityConfig.REORDER_MAX_BUFFER_SIZE
) {

    // 当前期待收到的下一个序列号
    var expectedSeqId: Long = initialExpectedSeqId
        private set

    // 重组缓冲区：使用小顶堆（PriorityQueue），根据 seqId 从小到大自动排序
    private val buffer = PriorityQueue<Message>(compareBy { it.seqId })

    /**
     * 接收并重组消息
     * @param message 新收到的消息
     * @return 返回可以按顺序交付给界面展示的消息列表
     */
    @Synchronized
    fun processMessage(message: Message): List<Message> {
        // 如果消息没有启用 seqId (即 seqId <= 0)，不做乱序重组，直接交付
        if (message.seqId <= 0L) {
            return listOf(message)
        }

        // 情况 A：收到了过期的老消息 (seqId < expectedSeqId)，直接丢弃
        if (message.seqId < expectedSeqId) {
            return emptyList()
        }

        // 将新消息放入小顶堆缓冲区
        buffer.add(message)

        val deliverableMessages = mutableListOf<Message>()

        // 核心检查循环：只要堆顶元素的 seqId 刚好等于期待的 expectedSeqId，开闸连带弹出
        while (buffer.isNotEmpty() && buffer.peek().seqId == expectedSeqId) {
            val nextMsg = buffer.poll()
            deliverableMessages.add(nextMsg)
            expectedSeqId++
        }

        // 防死锁保护：如果缺失消息彻底丢了，导致积压超过了 maxBufferSize，强制跳过缺失号！
        if (buffer.size > maxBufferSize) {
            val forcedMsg = buffer.poll()
            deliverableMessages.add(forcedMsg)
            expectedSeqId = forcedMsg.seqId + 1
        }

        return deliverableMessages
    }

    /**
     * 重置期待序列号与缓冲区
     */
    @Synchronized
    fun reset(startSeqId: Long = ReliabilityConfig.DEFAULT_START_SEQ_ID) {
        expectedSeqId = startSeqId
        buffer.clear()
    }
}
