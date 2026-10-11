package app.copy.data.storage

import app.copy.domain.ClipPolicy
import java.io.File
import java.io.FileNotFoundException

internal object ClipFiles {
    fun resolve(root: File, id: String): File {
        val file = File(root, id).canonicalFile
        if (file.parentFile != root.canonicalFile || !file.isFile || ClipPolicy.mime(file.name) == null) {
            throw FileNotFoundException("Clip unavailable")
        }
        return file
    }
}
