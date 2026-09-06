package koharia.importing

import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import koharia.connection.ConnectionMediaImportItem
import koharia.media.LocalMediaFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import java.io.File

internal class ImageComicScreenModel(private val context: Context, private val uris: List<String>) :
    StateScreenModel<ImageComicScreenModel.State>(State()) {
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var generated: File? = null

    init {
        screenModelScope.launchIO {
            try {
                val images = IncomingMediaParser.parse(context, uris)
                check(
                    images.size == uris.distinct().size && images.size >= 2 &&
                        images.all { LocalMediaFormats.isImage(it.extension) },
                )
                mutableState.update {
                    it.copy(
                        loading = false,
                        images = images.sortedWith { a, b ->
                            a.displayName.compareToCaseInsensitiveNaturalOrder(b.displayName)
                        },
                        title = context.stringResource(MR.strings.image_comic_default_title),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(loading = false, invalidSelection = true) }
            }
        }
    }

    fun title(value: String) {
        if (!state.value.building) mutableState.update { it.copy(title = value) }
    }

    fun move(index: Int, delta: Int) {
        if (state.value.building) return
        mutableState.update { state ->
            val target = index + delta
            if (index !in state.images.indices || target !in state.images.indices) {
                state
            } else {
                val images = state.images.toMutableList()
                images.add(target, images.removeAt(index))
                state.copy(images = images)
            }
        }
    }

    fun remove(index: Int) {
        if (!state.value.building) {
            mutableState.update {
                it.copy(images = it.images.filterIndexed { i, _ -> i != index })
            }
        }
    }

    fun build() {
        val snapshot = state.value
        if (!snapshot.canBuild) return
        mutableState.update { it.copy(building = true, completedPages = 0) }
        screenModelScope.launchIO {
            try {
                generated = ImageComicArchive.create(context, snapshot.title, snapshot.images) { pages ->
                    mutableState.update { it.copy(completedPages = pages) }
                }
                mutableState.update { it.copy(ready = true) }
                eventChannel.send(Event.Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                eventChannel.send(Event.Failed)
            } finally {
                mutableState.update { it.copy(building = false) }
            }
        }
    }

    fun takeArchive(): File? = generated.also { generated = null }

    override fun onDispose() {
        generated?.let { ImageComicArchive.deleteTemporary(context, it) }
        super.onDispose()
    }

    data class State(
        val loading: Boolean = true,
        val building: Boolean = false,
        val ready: Boolean = false,
        val invalidSelection: Boolean = false,
        val images: List<ConnectionMediaImportItem> = emptyList(),
        val title: String = "",
        val completedPages: Int = 0,
    ) {
        val canBuild: Boolean get() = !loading && !building && !ready && !invalidSelection && images.size >= 2 &&
            title.isNotBlank()
    }

    enum class Event { Ready, Failed }
}
