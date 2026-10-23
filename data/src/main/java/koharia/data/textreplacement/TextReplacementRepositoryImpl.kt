package koharia.data.textreplacement

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import koharia.domain.textreplacement.StoredReplacementScope
import koharia.domain.textreplacement.TextReplacementEngine
import koharia.domain.textreplacement.TextReplacementRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.Database

class TextReplacementRepositoryImpl(private val database: Database) : TextReplacementRepository {
    private val json = Json { ignoreUnknownKeys = true }
    override fun observe() = database.text_replacementQueries.selectAll().asFlow()
        .mapToList(Dispatchers.IO).map { rows -> rows.map { json.decodeFromString<StoredReplacementScope>(it) } }

    override suspend fun all() = database.text_replacementQueries.selectAll().awaitAsList()
        .map { json.decodeFromString<StoredReplacementScope>(it) }

    override suspend fun save(scopes: List<StoredReplacementScope>) {
        scopes.forEach { entry ->
            require(entry.key == entry.context.key(entry.scope))
            require(entry.state.rules.map { it.key }.distinct().size == entry.state.rules.size) { "duplicate_rule" }
            require(entry.state.rules.map { it.id }.distinct().size == entry.state.rules.size) { "duplicate_id" }
            entry.state.rules.filter {
                it.enabled
            }.forEach { require(TextReplacementEngine.validate(it) == null) { "invalid_rule" } }
        }
        database.transaction {
            scopes.forEach { database.text_replacementQueries.upsert(it.key, json.encodeToString(it)) }
        }
    }
}
