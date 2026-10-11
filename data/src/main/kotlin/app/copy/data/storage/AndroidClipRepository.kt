package app.copy.data.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.copy.domain.Clip
import app.copy.domain.ClipPolicy
import app.copy.domain.ClipRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidClipRepository(private val context: Context) : ClipRepository {
    private val mutableClips = MutableStateFlow<List<Clip>>(emptyList())
    private val mutableImporting = MutableStateFlow(false)
    private val importLock = Mutex()
    private val refreshLock = Mutex()
    private val metadata = context.getSharedPreferences("clips", Context.MODE_PRIVATE)
    override val clips = mutableClips.asStateFlow()
    override val importing = mutableImporting.asStateFlow()
    internal val directory: File get() = directory(context)

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            mutableClips.value = directory.listFiles().orEmpty()
                .filter { it.isFile && ClipPolicy.mime(it.name) != null }
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    Clip(file.name, file.name, file.length(), file.lastModified(),
                        requireNotNull(ClipPolicy.mime(file.name)), uri(context, file.name).toString(),
                        metadata.getBoolean(file.name, false))
                }
        }
    }

    override suspend fun importClip(sourceUri: String) {
        check(importLock.tryLock()) { "An import is already running" }
        mutableImporting.value = true
        try {
            withContext(Dispatchers.IO) {
                val uri = Uri.parse(sourceUri)
                val extension = ClipPolicy.extension(context.contentResolver.getType(uri))
                val temp = File.createTempFile("import-", ".part", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw IOException("Cannot open source file")
                        temp.outputStream().use { output ->
                            val bytes = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                total += count
                                if (total > ClipPolicy.MAX_IMPORT_BYTES || directory.usableSpace < 64_000_000L) {
                                    throw IOException("File is too large or storage is low")
                                }
                                output.write(bytes, 0, count)
                            }
                        }
                    }
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(temp.absolutePath)
                        check((0 until extractor.trackCount).any {
                            val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                            mime.startsWith("audio/") || mime.startsWith("video/")
                        }) { "This file has no readable media tracks" }
                    } finally { extractor.release() }
                    val destination = File(directory, "Import-${UUID.randomUUID()}.$extension")
                    if (!temp.renameTo(destination)) throw IOException("Cannot save imported clip")
                    refresh()
                } finally { temp.delete() }
            }
        } finally { mutableImporting.value = false; importLock.unlock() }
    }

    override suspend fun exportClip(id: String, destinationUri: String) = withContext(Dispatchers.IO) {
        val file = ClipFiles.resolve(directory, id)
        context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt").use { output ->
            if (output == null) throw IOException("Cannot open destination")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes, 0, count)
                }
            }
        }
    }

    override suspend fun deleteClip(id: String) = withContext(Dispatchers.IO) {
        if (!ClipFiles.resolve(directory, id).delete()) throw IOException("Could not delete clip")
        metadata.edit().remove(id).apply()
        refresh()
    }

    internal suspend fun recorded(file: File, silent: Boolean) {
        metadata.edit().putBoolean(file.name, silent).apply()
        refresh()
    }

    companion object {
        internal fun directory(context: Context): File = File(context.filesDir, "clips").also {
            check(it.isDirectory || it.mkdirs()) { "Cannot create clip folder" }
        }
        internal fun uri(context: Context, id: String): Uri = Uri.Builder().scheme("content")
            .authority("${context.packageName}.clips").appendPath(id).build()
    }
}
