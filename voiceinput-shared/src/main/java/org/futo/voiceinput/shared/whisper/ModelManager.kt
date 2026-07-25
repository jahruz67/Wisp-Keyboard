package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.types.ModelLoader
import java.util.concurrent.ConcurrentHashMap


class ModelManager(
    val context: Context
) {
    private val loadedModels = ConcurrentHashMap<Any, WhisperGGML>()

    @Synchronized
    fun obtainModel(model: ModelLoader): WhisperGGML {
        val key = model.key(context)
        return loadedModels[key]
            ?: model.loadGGML(context).also { loadedModels[key] = it }
    }

    fun cancelAll() {
        loadedModels.values.forEach { it.cancel() }
    }

    suspend fun cleanUp() {
        val models = synchronized(this) {
            loadedModels.values.toList().also { loadedModels.clear() }
        }
        for (model in models) {
            model.cancel()
            model.close()
        }
    }
}
