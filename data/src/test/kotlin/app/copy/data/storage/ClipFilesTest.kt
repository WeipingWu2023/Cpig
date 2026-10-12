package app.copy.data.storage

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun shareResolutionRejectsTraversalAndIncompleteFiles() {
        val root = temp.newFolder("clips")
        val valid = File(root, "valid.mp4").apply { writeBytes(byteArrayOf(1)) }
        File(temp.root, "outside.mp4").writeBytes(byteArrayOf(1))
        File(root, "unfinished.part").writeBytes(byteArrayOf(1))
        assertEquals(valid.canonicalFile, ClipFiles.resolve(root, valid.name))
        val screenshot = File(root, "screenshot.png").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(screenshot.canonicalFile, ClipFiles.resolve(root, screenshot.name))
        assertThrows(FileNotFoundException::class.java) { ClipFiles.resolve(root, "../outside.mp4") }
        assertThrows(FileNotFoundException::class.java) { ClipFiles.resolve(root, "unfinished.part") }
        assertThrows(FileNotFoundException::class.java) { ClipFiles.resolve(root, "missing.mp4") }
    }
}
