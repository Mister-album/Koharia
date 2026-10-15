package koharia.connection

import androidx.paging.Pager
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionShelfPagingTest {
    @Test
    fun `bounded pager drops forward pages then restores every old page without refetching`() = runTest {
        for ((remoteSize, prefetch) in listOf(25 to 25, 50 to 25, 50 to 5, 100 to 5, 50 to 50)) {
            val stored = mutableMapOf<Int, List<Int>>()
            var requests = 0
            val config = connectionShelfPagingConfig(
                pageSize = if (prefetch == 25) 25 else 50,
                prefetchDistance = prefetch,
            )
            val source = object : PagingSource<Int, Int>() {
                override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                    val page = params.key ?: 0
                    val result = stored.getOrPut(page) {
                        requests++
                        (page * remoteSize until (page + 1) * remoteSize).toList()
                    }
                    return LoadResult.Page(result, (page - 1).takeIf { page > 0 }, (page + 1).takeIf { page < 39 })
                }
            }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val presenter = object : PagingDataPresenter<Int>(dispatcher) {
                override suspend fun presentPagingDataEvent(event: PagingDataEvent<Int>) = Unit
            }
            val job = launch(dispatcher) { Pager(config) { source }.flow.collectLatest(presenter::collectFrom) }
            try {
                advanceUntilIdle()
                repeat(50) {
                    presenter[presenter.size - 1]
                    advanceUntilIdle()
                    assertTrue(presenter.size <= config.maxSize, "size=${presenter.size}, page=$remoteSize")
                }
                assertEquals(40 * remoteSize - 1, presenter.snapshot().items.last())
                assertTrue(presenter.snapshot().items.first() > 0)
                val before = requests
                repeat(50) {
                    presenter[0]
                    advanceUntilIdle()
                    assertTrue(presenter.size <= config.maxSize)
                }
                assertEquals(0, presenter.snapshot().items.first())
                assertEquals(before, requests)
            } finally {
                job.cancel()
            }
        }
    }
}
