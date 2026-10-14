package au.com.ausroads.ui.explore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Only explicit user actions write visits. Search, route-start and GPS never do. */
data class ExplorationPreferences(
    val interests: Set<ExploreCategory> = emptySet(),
    val visits: Map<String, ExploreCategory> = emptyMap(),
    val preferNew: Boolean = true,
)

interface ExplorationStore {
    val preferences: Flow<ExplorationPreferences>

    suspend fun setInterest(category: ExploreCategory, enabled: Boolean)

    suspend fun setPreferNew(enabled: Boolean)

    suspend fun setVisited(result: SearchResult, visited: Boolean)

    suspend fun clearVisits()
}

/** A refused extra visit, not a storage failure or permission to evict older history. */
class VisitLimitReachedException : IllegalStateException("Visit history limit reached")

private val Context.explorationDataStore by preferencesDataStore(name = "exploration_v1")

@Singleton
class DataStoreExplorationStore @Inject constructor(@ApplicationContext context: Context) :
    ExplorationStore {
    private val dataStore = context.explorationDataStore

    override val preferences: Flow<ExplorationPreferences> =
        dataStore.data.map { values ->
            ExplorationPreferences(
                interests =
                    (values[INTERESTS] ?: emptySet())
                        .mapNotNull { name ->
                            ExploreCategory.entries.firstOrNull { it.name == name }
                        }
                        .toSet(),
                visits = decodeVisits(values[VISITS] ?: emptySet()),
                preferNew = values[PREFER_NEW] ?: true,
            )
        }

    override suspend fun setInterest(category: ExploreCategory, enabled: Boolean) {
        dataStore.edit { values ->
            val interests = (values[INTERESTS] ?: emptySet()).toMutableSet()
            if (enabled) interests += category.name else interests -= category.name
            values[INTERESTS] = interests
        }
    }

    override suspend fun setPreferNew(enabled: Boolean) {
        dataStore.edit { it[PREFER_NEW] = enabled }
    }

    override suspend fun setVisited(result: SearchResult, visited: Boolean) {
        val key = result.placeKey()
        val category = requireNotNull(ExploreCategory.forClass(result.className))
        dataStore.edit { values ->
            val visits = updatedVisits(decodeVisits(values[VISITS] ?: emptySet()), key, category, visited)
            values[VISITS] = visits.map { (id, interest) -> "$id;${interest.name}" }.toSet()
        }
    }

    override suspend fun clearVisits() {
        dataStore.edit { it[VISITS] = emptySet() }
    }

    companion object {
        const val MAX_VISITS = 500
        private val INTERESTS = stringSetPreferencesKey("interests")
        private val VISITS = stringSetPreferencesKey("visits")
        private val PREFER_NEW = booleanPreferencesKey("prefer_new")
        private val KEY_PATTERN = Regex("v1:[a-f0-9]{64}")

        internal fun updatedVisits(
            visits: Map<String, ExploreCategory>,
            key: String,
            category: ExploreCategory,
            visited: Boolean,
        ): Map<String, ExploreCategory> {
            if (visited && key !in visits && visits.size >= MAX_VISITS) throw VisitLimitReachedException()
            return if (visited) visits + (key to category) else visits - key
        }

        internal fun decodeVisits(records: Set<String>): Map<String, ExploreCategory> {
            check(records.size <= MAX_VISITS) { "Invalid visit history" }
            return buildMap {
                records.forEach { record ->
                    val parts = record.split(';')
                    check(parts.size == 2 && KEY_PATTERN.matches(parts[0])) { "Invalid visit record" }
                    val category = ExploreCategory.entries.firstOrNull { it.name == parts[1] }
                    check(put(parts[0], checkNotNull(category) { "Invalid visit category" }) == null) {
                        "Conflicting visit records"
                    }
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExplorationModule {
    @Binds abstract fun bindExplorationStore(impl: DataStoreExplorationStore): ExplorationStore
}
