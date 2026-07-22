package com.tavernchat.core.reliability

/**
 * 幂等去重器 (基于 LRU 最近最少使用算法实现的动态滑动窗口去重)
 *
 * 💡【架构设计说明】：
 * 1. 为什么选择 LinkedHashMap？
 *    LinkedHashMap 在 HashSet 基础上引入了双向链表，兼具 O(1) 极速查重
 *    与“满了自动把最老数据往前挤（淘汰）”的能力，形成了限定容量的动态去重滑动窗口。
 *
 * @param capacity 动态去重滑动窗口的最大容量，默认从 ReliabilityConfig 统一读取
 */
class Deduplicator(private val capacity: Int = ReliabilityConfig.DEDUPLICATOR_CAPACITY) {

    // 彻底消除 0.75f 魔法数字，传入全局配置 ReliabilityConfig.DEFAULT_LOAD_FACTOR
    private val seenMessageIds = object : LinkedHashMap<String, Boolean>(
        capacity,
        ReliabilityConfig.DEFAULT_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > capacity
        }
    }

    /**
     * 检查消息是否重复
     * @return 重复返回 true；新消息返回 false。
     */
    @Synchronized
    fun isDuplicate(messageId: String): Boolean {
        if (messageId.isBlank()) return false

        if (seenMessageIds.containsKey(messageId)) {
            return true
        }

        seenMessageIds[messageId] = true
        return false
    }

    @Synchronized
    fun clear() {
        seenMessageIds.clear()
    }
}
