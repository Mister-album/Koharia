package koharia.domain.textreplacement

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class StoredReplacementScope(
    val key: String,
    val scope: ReplacementScope,
    val context: TextReplacementContext,
    val state: ReplacementScopeState,
)

interface TextReplacementRepository {
    fun observe(): Flow<List<StoredReplacementScope>>
    suspend fun all(): List<StoredReplacementScope>
    suspend fun save(scopes: List<StoredReplacementScope>)
}
