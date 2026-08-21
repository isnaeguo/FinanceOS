package com.financeos.shared.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CategoryTest {
    @Test
    fun rejectsBlankRequiredValues() {
        assertFailsWith<IllegalArgumentException> {
            category(id = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            category(name = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            category(iconKey = " ")
        }
    }

    @Test
    fun defaultCategoriesHaveStableUniqueIds() {
        val categories = DefaultCategories.all

        assertEquals(10, categories.size)
        assertEquals(categories.size, categories.map(Category::id).toSet().size)
        assertTrue(categories.all(Category::isSystem))
    }

    @Test
    fun defaultCategoriesCoverAllCategoryTypes() {
        val types = DefaultCategories.all.map(Category::type).toSet()

        assertEquals(CategoryType.entries.toSet(), types)
    }

    private fun category(
        id: String = "category-id",
        name: String = "分类",
        iconKey: String = "category-icon",
    ) = Category(
        id = id,
        name = name,
        type = CategoryType.EXPENSE,
        iconKey = iconKey,
        isSystem = false,
    )
}
