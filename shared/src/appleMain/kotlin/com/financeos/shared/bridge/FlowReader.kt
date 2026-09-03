package com.financeos.shared.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction

/**
 * 把 Kotlin [Flow] 转成 Swift 可逐个拉取的挂起迭代器。
 *
 * KMP 框架默认不导出协程的响应式语义，Swift 侧用 `AsyncStream` 包装 [next] 即可把
 * Room 的响应式查询接到 `@Observable` 状态上。三个具体子类避免 Swift 侧处理泛型。
 */
public sealed class FlowReader<T : Any>(flow: Flow<T>) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel: Channel<T> = Channel(Channel.UNLIMITED)

    init {
        scope.launch {
            flow.collect { channel.send(it) }
            channel.close()
        }
    }

    /** 等待并返回下一个值；流结束或读取器关闭后返回 `null`。 */
    public suspend fun next(): T? = channel.receiveCatching().getOrNull()

    override fun close() {
        scope.cancel()
        channel.close()
    }
}

/** 流水全量响应式读取器。 */
public class TransactionListReader(flow: Flow<List<Transaction>>) : FlowReader<List<Transaction>>(flow)

/** 分类全量响应式读取器。 */
public class CategoryListReader(flow: Flow<List<Category>>) : FlowReader<List<Category>>(flow)

/** 预算全量响应式读取器。 */
public class BudgetListReader(flow: Flow<List<Budget>>) : FlowReader<List<Budget>>(flow)
