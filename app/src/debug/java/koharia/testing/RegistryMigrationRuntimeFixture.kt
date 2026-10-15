package koharia.testing

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.function.Consumer

object RegistryMigrationRuntimeFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, status: Consumer<String>): Unit = runBlocking(Dispatchers.IO) {
        check(context.packageName == "app.koharia.dev.devicefixture")
        val expected = setOf("komga", "lanraragi", "suwayomi", "kavita", "smanga", "local-folder")
        val registry = Injekt.get<ConnectionRegistry>()
        check(registry.availableProviders().map { it.id }.toSet() == expected)
        val json = Injekt.get<Json>()
        expected.sorted().forEachIndexed { index, provider ->
            val profile = LibraryConnectionProfile(-(index + 2L), provider, "Owned 中文 fixture $provider")
            check(json.decodeFromString<LibraryConnectionProfile>(json.encodeToString(profile)) == profile)
            check(registry.provider(profile.providerId)?.id == provider)
        }
        status.accept("six-provider-registry;profile-json-roundtrip")
        check(Database.Schema.version == 33L) { "Update the migration fixture for the current schema" }
        val name = "registry-migration-runtime-${UUID.randomUUID()}.db"
        check(!context.getDatabasePath(name).exists())
        val previous = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
            override val version = 32L
            override fun create(driver: SqlDriver) = QueryResult.AsyncValue<Unit> {
                Database.Schema.create(driver).await()
                driver.execute(null, "DROP INDEX lanraragi_read_state_pending_index", 0).await()
                driver.execute(null, "ALTER TABLE lanraragi_read_state DROP COLUMN requires_confirmation", 0).await()
                driver.execute(
                    null,
                    "CREATE INDEX lanraragi_read_state_pending_index " +
                        "ON lanraragi_read_state(connection_id,pending,local_unread)",
                    0,
                ).await()
            }
            override fun migrate(
                driver: SqlDriver,
                oldVersion: Long,
                newVersion: Long,
                vararg callbacks: app.cash.sqldelight.db.AfterVersion,
            ) = QueryResult.AsyncValue<Unit> { error("Disposable fixture must start fresh") }
        }
        fun open(schema: SqlSchema<QueryResult.AsyncValue<Unit>>) = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = schema,
        )
        try {
            open(previous).use { driver ->
                driver.execute(null, "INSERT INTO lanraragi_read_state VALUES (1,'book',3,10,100,0,1,4,0)", 0).await()
            }
            repeat(2) { attempt ->
                open(Database.Schema).use { driver ->
                    check(
                        driver.strings("SELECT name FROM pragma_index_info('lanraragi_read_state_pending_index')") ==
                            listOf("connection_id", "pending", "local_unread", "requires_confirmation"),
                    )
                    check(
                        driver.strings(
                            "SELECT page_index || ':' || revision || ':' || pending || ':' || requires_confirmation " +
                                "FROM lanraragi_read_state",
                        ) == listOf("3:4:1:0"),
                    )
                }
                status.accept(
                    if (attempt ==
                        0
                    ) {
                        "schema32-to33;pending-preserved;index-updated"
                    } else {
                        "database-reopened"
                    },
                )
            }
            status.accept("debuggable=${context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
        } finally {
            check(context.deleteDatabase(name))
            check(!context.getDatabasePath(name).exists())
            status.accept("owned-database-cleaned")
        }
    }

    private suspend fun SqlDriver.strings(sql: String): List<String> = executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0).orEmpty()) })
    }, 0).await()
}
