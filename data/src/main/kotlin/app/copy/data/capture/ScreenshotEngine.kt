package app.copy.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import app.copy.data.storage.AndroidClipRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** One consent session, one display, one lossless PNG. No audio permission is required. */
internal class ScreenshotEngine(private val context: Context, private val projection: MediaProjection) {
    suspend fun capture(onSaving: () -> Unit): File {
        val partial = File(AndroidClipRepository.directory(context), "Copig-${UUID.randomUUID()}.part")
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var bitmap: Bitmap? = null
        try {
            if (context.filesDir.usableSpace < 64_000_000L) throw IOException("Free at least 64 MB before taking a screenshot")
            // Let the floating controls disappear before acquiring the screen.
            delay(300)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            val frames = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            reader = frames
            display = projection.createVirtualDisplay("Copig screenshot", metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, frames.surface, null, null)
            delay(100)
            var image: Image? = null
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (image == null) {
                currentCoroutineContext().ensureActive()
                image = frames.acquireLatestImage()
                if (image == null) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("No screenshot frame arrived. Approve screen capture and try again.")
                    delay(20)
                }
            }
            requireNotNull(image).use { frame ->
                val plane = frame.planes.firstOrNull() ?: throw IOException("No screenshot pixels arrived")
                val pixels = ScreenshotPixels.packedRgba(plane.buffer, frame.width, frame.height, plane.pixelStride, plane.rowStride)
                bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(pixels)
                }
            }
            display?.release()
            display = null
            frames.close()
            reader = null
            currentCoroutineContext().ensureActive()
            onSaving()
            partial.outputStream().use { output ->
                if (!requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not save screenshot")
            }
            currentCoroutineContext().ensureActive()
            val screenshot = File(partial.parentFile, partial.name.removeSuffix(".part") + ".png")
            if (!partial.renameTo(screenshot)) throw IOException("Could not finalize screenshot")
            return screenshot
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { bitmap?.recycle() }
            partial.delete()
        }
    }
}
