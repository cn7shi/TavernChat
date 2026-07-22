package com.tavernchat.core.reliability

/**
 * 可靠传输引擎全局配置常量类
 * 统一管理系统所有的容量上限、超时时间与哈希参数，彻底消除魔法数字。
 */
object ReliabilityConfig {
    /** 幂等去重滑动窗口的默认最大容量上限 */
    const val DEDUPLICATOR_CAPACITY = 50

    /** 哈希表默认负载因子 (Load Factor)，0.75f 为时间和空间效率的黄金平衡点 */
    const val DEFAULT_LOAD_FACTOR = 0.75f

    /** 乱序重组缓冲区的默认最大积压容量上限（防死锁保护） */
    const val REORDER_MAX_BUFFER_SIZE = 50

    /** 默认乱序重组起始序列号 */
    const val DEFAULT_START_SEQ_ID = 1L

    /** 默认重试等待毫秒数 */
    const val DEFAULT_RETRY_DELAY_MS = 1000L

    /** 默认最大重试次数 */
    const val DEFAULT_MAX_RETRIES = 5
}
