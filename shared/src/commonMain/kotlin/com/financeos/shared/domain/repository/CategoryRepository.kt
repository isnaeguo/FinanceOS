package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.Category

/** 定义分类读取所需的最小业务能力。 */
interface CategoryRepository {
    /** 按 ID 获取分类，不存在时返回 `null`。 */
    suspend fun get(id: String): Category?

    /** 获取全部分类。 */
    suspend fun getAll(): List<Category>
}
