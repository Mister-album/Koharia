package koharia.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Every database and injected lock belongs to this test, never to the fixture application's database. */
@RunWith(AndroidJUnit4::class)
class DatabaseWriterCancellationDeviceTest {
    @Test
    fun readerFallbackTimeoutDoesNotLeakWriter(): Unit = runBlocking(Dispatchers.IO) {
        withDatabase { driver, _ ->
            val pool = driver.pool()
            val readers = mutableListOf<SQLiteConnection>()
            val writerField = pool.javaClass.getDeclaredField("lazyWriterConnection").apply { isAccessible = true }
            val original = writerField.get(pool) as Lazy<*>
            val writer = original.value as SQLiteConnection
            try {
                repeat(3) { readers += pool.call<SQLiteConnection>("acquireReaderConnection") }
                // Stretch the acquire/timeout boundary deterministically, without altering the driver's algorithm.
                writerField.set(
                    pool,
                    lazy {
                        Thread.sleep(150)
                        writer
                    },
                )
                pool.writerMutex().lock()
                val releaseWriter = launch {
                    delay(10)
                    pool.writerMutex().unlock()
                }
                val borrowed = withTimeoutOrNull(1_000) {
                    pool.call<SQLiteConnection>("acquireReaderConnection")
                }
                releaseWriter.join()
                if (borrowed != null) pool.call<Unit>("releaseReaderConnection", borrowed)
                val leaked = pool.writerMutex().isLocked
                println("Reader fallback: returned=${borrowed != null}, writerLocked=$leaked")
                assertFalse("Timed-out reader fallback stranded the writer mutex", leaked)
                assertNotNull("Reader fallback must return a usable connection", borrowed)
            } finally {
                readers.forEach { pool.call<Unit>("releaseReaderConnection", it) }
                writerField.set(pool, original)
            }
            driver.assertWritable()
        }
    }

    @Test
    fun cancellationAfterBeginReleasesWriter(): Unit = runBlocking(Dispatchers.IO) {
        withDatabase { driver, afterBegin ->
            var bodyStarted = false
            val worker = launch {
                val context = currentCoroutineContext()
                afterBegin.set { context.cancel() }
                object : SuspendingTransacterImpl(driver) {}.transaction {
                    bodyStarted = true
                    driver.execute(null, "INSERT INTO fixture VALUES (1)", 0).await()
                }
            }
            withTimeout(5_000) { worker.join() }
            assertFalse("Cancellation must happen before the transaction body", bodyStarted)
            val leaked = driver.pool().writerMutex().isLocked
            println("Cancelled transaction startup: writerLocked=$leaked")
            assertFalse("Cancellation after BEGIN stranded the writer mutex", leaked)
            driver.assertWritable()
        }
    }

    @Test
    fun cancelledQueuedWritesAndRollbackLeaveDatabaseUsable(): Unit = runBlocking(Dispatchers.IO) {
        withDatabase { driver, _ ->
            val transacter = object : SuspendingTransacterImpl(driver) {}
            repeat(20) {
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val worker = launch {
                    transacter.transaction {
                        driver.execute(null, "INSERT INTO fixture VALUES (99)", 0).await()
                        entered.complete(Unit)
                        delay(Long.MAX_VALUE)
                    }
                }
                withTimeout(5_000) { entered.await() }
                val queued = List(8) { launch(start = CoroutineStart.UNDISPATCHED) { driver.assertWritable() } }
                queued.forEach { it.cancelAndJoin() }
                worker.cancelAndJoin()
                driver.assertWritable()
            }
            val rolledBackRows = driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM fixture WHERE value = 99",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else -1L) },
                0,
            ).await()
            assertEquals(0L, rolledBackRows)
        }
    }

    private suspend fun withDatabase(block: suspend (AndroidxSqliteDriver, AtomicReference<(() -> Unit)?>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "writer-cancellation-${System.nanoTime()}.db"
        val afterBegin = AtomicReference<(() -> Unit)?>(null)
        val bundled = BundledSQLiteDriver()
        val driver = AndroidxSqliteDriver(
            driver = object : SQLiteDriver by bundled {
                override fun open(fileName: String): SQLiteConnection {
                    val connection = bundled.open(fileName)
                    return object : SQLiteConnection by connection {
                        override fun prepare(sql: String): SQLiteStatement {
                            val statement = connection.prepare(sql)
                            return object : SQLiteStatement by statement {
                                override fun step(): Boolean = statement.step().also {
                                    if (sql == "BEGIN IMMEDIATE") afterBegin.getAndSet(null)?.invoke()
                                }
                            }
                        }
                    }
                }
            },
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
                override val version = 1L
                override fun create(driver: SqlDriver) = QueryResult.AsyncValue {
                    driver.execute(null, "CREATE TABLE fixture(value INTEGER NOT NULL)", 0).await()
                    Unit
                }

                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: app.cash.sqldelight.db.AfterVersion,
                ) = QueryResult.AsyncValue { Unit }
            },
        )
        try {
            driver.assertWritable()
            block(driver, afterBegin)
        } finally {
            afterBegin.set(null)
            // A failing regression must not hang driver.close(). Only repair this disposable test pool.
            val pool = driver.pool()
            if (pool.writerMutex().isLocked) {
                val writer = pool.field("lazyWriterConnection") as Lazy<*>
                runCatching { (writer.value as SQLiteConnection).execSQL("ROLLBACK") }
                pool.writerMutex().unlock()
            }
            driver.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun AndroidxSqliteDriver.assertWritable() = withTimeout(5_000) {
        execute(null, "INSERT INTO fixture VALUES (0)", 0).await()
        Unit
    }

    private fun Any.field(name: String): Any = javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(this)!!

    private fun AndroidxSqliteDriver.pool(): Any = (field("connectionPool\$delegate") as Lazy<*>).value!!

    private fun Any.writerMutex() = field("writerMutex") as Mutex

    private suspend inline fun <reified T> Any.call(name: String, vararg arguments: Any): T =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = javaClass.declaredMethods.single {
                it.name == name && it.parameterTypes.lastOrNull() == Continuation::class.java
            }.apply { isAccessible = true }
            try {
                method.invoke(this, *arguments, continuation)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
}
