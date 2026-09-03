package com.financeos.shared.data.local

import androidx.room3.Room
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.time.EpochClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 合并语义测试矩阵：裁决只依赖 (updatedAt, 规范化内容)，与到达顺序无关。
 * 覆盖新者胜、旧者不覆盖、墓碑胜于旧数据（防复活）、相等内容确定性收敛、双端墓碑，
 * 以及整体交换律 merge(A,B) == merge(B,A)。
 */
class MergeSemanticsTest {
    @Test
    fun newerRemoteReplacesLocal() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalFinanceDataRepository(database)
            repository.merge(singleSnapshot(alive("tx-1", updatedAt = 100L, note = "旧")))
            repository.merge(singleSnapshot(alive("tx-1", updatedAt = 200L, note = "新")))

            val merged = repository.snapshot().transactions.single()
            assertEquals("新", merged.note)
            assertEquals(200L, merged.updatedAt)
            assertNull(merged.deletedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun olderRemoteDoesNotOverrideLocal() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalFinanceDataRepository(database)
            repository.merge(singleSnapshot(alive("tx-1", updatedAt = 200L, note = "本地新")))
            repository.merge(singleSnapshot(alive("tx-1", updatedAt = 100L, note = "远端旧")))

            val merged = repository.snapshot().transactions.single()
            assertEquals("本地新", merged.note)
            assertEquals(200L, merged.updatedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun remoteTombstoneDeletesLocalRecordAndPreventsResurrection() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalFinanceDataRepository(database)
            val transactionRepository = LocalTransactionRepository(database.transactionDao())
            repository.merge(singleSnapshot(alive("tx-1", updatedAt = 100L)))
            // 远端已删除（墓碑更新），合并后本地必须转为删除，业务读取不再出现。
            repository.merge(
                singleSnapshot(alive("tx-1", updatedAt = 200L).copy(deletedAt = 200L)),
            )

            assertNull(transactionRepository.get("tx-1"))
            assertTrue(transactionRepository.getAll().isEmpty())
            // 墓碑仍在快照中：它必须继续传播，否则第三方快照会把记录复活。
            val tombstone = repository.snapshot().transactions.single()
            assertEquals(200L, tombstone.deletedAt)
            assertEquals(200L, tombstone.updatedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun equalUpdatedAtConvergesToSameWinnerFromEitherDirection() = runTest {
        val first = openMemoryDatabase()
        val second = openMemoryDatabase()
        try {
            val firstRepository = LocalFinanceDataRepository(first)
            val secondRepository = LocalFinanceDataRepository(second)
            val localVersion = alive("tx-1", updatedAt = 100L, note = "甲")
            val remoteVersion = alive("tx-1", updatedAt = 100L, note = "乙")
            firstRepository.merge(singleSnapshot(localVersion))
            secondRepository.merge(singleSnapshot(remoteVersion))

            // 双端各持一版，同一次相互合并后必须收敛到同一条记录。
            firstRepository.merge(singleSnapshot(remoteVersion))
            secondRepository.merge(singleSnapshot(localVersion))

            assertEquals(
                firstRepository.snapshot().transactions.single(),
                secondRepository.snapshot().transactions.single(),
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun tombstonesOnBothSidesStayDeleted() = runTest {
        val database = openMemoryDatabase()
        try {
            val repository = LocalFinanceDataRepository(database)
            val transactionRepository = LocalTransactionRepository(database.transactionDao())
            val localTombstone = alive("tx-1", updatedAt = 100L).copy(deletedAt = 100L)
            val remoteTombstone = alive("tx-1", updatedAt = 100L, note = "远端墓碑").copy(deletedAt = 100L)
            repository.merge(singleSnapshot(localTombstone))
            repository.merge(singleSnapshot(remoteTombstone))

            assertNull(transactionRepository.get("tx-1"))
            assertTrue(transactionRepository.getAll().isEmpty())
            assertTrue(repository.snapshot().transactions.single().deletedAt != null)
        } finally {
            database.close()
        }
    }

    @Test
    fun mergeResultIsIndependentOfArrivalOrder() = runTest {
        val snapshots = listOf(
            singleSnapshot(alive("tx-1", updatedAt = 100L, note = "一")),
            singleSnapshot(alive("tx-1", updatedAt = 300L, note = "三")),
            singleSnapshot(alive("tx-2", updatedAt = 200L).copy(deletedAt = 250L)),
            singleSnapshot(alive("tx-2", updatedAt = 150L, note = "二")),
            singleSnapshot(alive("tx-3", updatedAt = 120L, note = "三号")),
        )
        val first = openMemoryDatabase()
        val second = openMemoryDatabase()
        try {
            val firstRepository = LocalFinanceDataRepository(first)
            val secondRepository = LocalFinanceDataRepository(second)
            snapshots.forEach { firstRepository.merge(it) }
            snapshots.reversed().forEach { secondRepository.merge(it) }

            assertEquals(
                firstRepository.snapshot().transactions.associateBy { it.id },
                secondRepository.snapshot().transactions.associateBy { it.id },
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun localSoftDeletePropagatesToOtherDevice() = runTest {
        val local = openMemoryDatabase()
        val remote = openMemoryDatabase()
        try {
            val localRepository = LocalFinanceDataRepository(local)
            val remoteRepository = LocalFinanceDataRepository(remote)
            val remoteTransactionRepository = LocalTransactionRepository(
                remote.transactionDao(),
                EpochClock { SOFT_DELETE_AT_MILLIS },
            )
            val record = alive("tx-1", updatedAt = 100L)
            localRepository.merge(singleSnapshot(record))
            remoteRepository.merge(singleSnapshot(record))

            // A 端软删后导出快照推给 B 端，B 端合并后业务读取不再出现该记录。
            localRepository.merge(
                FinanceDataSnapshot(
                    transactions = listOf(
                        localRepository.snapshot().transactions.single().let {
                            // 模拟 A 端在本端执行软删。
                            it.copy(deletedAt = SOFT_DELETE_AT_MILLIS, updatedAt = SOFT_DELETE_AT_MILLIS)
                        },
                    ),
                    categories = emptyList(),
                    budgets = emptyList(),
                ),
            )
            remoteRepository.merge(localRepository.snapshot())

            assertNull(remoteTransactionRepository.get("tx-1"))
            assertTrue(remoteRepository.snapshot().transactions.single().deletedAt != null)
        } finally {
            local.close()
            remote.close()
        }
    }

    private fun alive(
        id: String,
        updatedAt: Long,
        note: String = "备注",
    ): Transaction = Transaction(
        id = id,
        amount = 1_299L,
        type = TransactionType.EXPENSE,
        categoryId = "system-food",
        dateTime = Instant.parse("2026-08-10T08:30:00Z"),
        note = note,
        updatedAt = updatedAt,
    )

    private fun singleSnapshot(transaction: Transaction) = FinanceDataSnapshot(
        transactions = listOf(transaction),
        categories = emptyList(),
        budgets = emptyList(),
    )

    private fun openMemoryDatabase(): FinanceOsDatabase = buildFinanceOsDatabase(
        Room.inMemoryDatabaseBuilder {
            FinanceOsDatabaseConstructor.initialize()
        },
    )

    private companion object {
        const val SOFT_DELETE_AT_MILLIS = 1_756_896_000_000L
    }
}
