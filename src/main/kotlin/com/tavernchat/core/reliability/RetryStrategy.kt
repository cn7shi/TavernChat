package com.tavernchat.core.reliability

import kotlin.math.pow

/**
 * 重试延迟策略接口
 * 定义了计算重试等待时间的算法契约
 */
interface RetryStrategy {
    /**
     * 根据当前已重试的次数计算下一次重试前的等待延迟（毫秒）。
     * 如果返回 -1，表示达到最大重试限制，应该停止重试并宣告失败。
     */
    fun nextDelay(retryCount: Int): Long
}

/**
 * 固定间隔重试策略
 * @param delayMs 每次重试等待的固定毫秒数
 * @param maxRetries 最大重试次数
 */
class FixedDelayRetryStrategy(
    private val delayMs: Long,
    private val maxRetries: Int
) : RetryStrategy {
    override fun nextDelay(retryCount: Int): Long {
        return if (retryCount < maxRetries) delayMs else -1L
    }
}

/**
 * 指数退避重试策略（重试等待时间随重试次数呈指数增长）
 * @param baseDelayMs 基础延迟毫秒数 (例如 1000ms)
 * @param maxRetries 最大重试次数
 */
class ExponentialBackoffRetryStrategy(
    private val baseDelayMs: Long,
    private val maxRetries: Int
) : RetryStrategy {
    override fun nextDelay(retryCount: Int): Long {
        if (retryCount >= maxRetries) return -1L
        return (baseDelayMs * 2.0.pow(retryCount)).toLong()
    }
}
