package app.copy.data.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import app.copy.data.storage.AndroidClipRepository
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class RecordedClip(val file: File, val audioAppearsSilent: Boolean)

/** Runs on the service's IO dispatcher, independently of the UI lifecycle. */
internal class CaptureEngine(private val context: Context, private val projection: MediaProjection) {
    @Volatile private var stop = false
    fun requestStop() { stop = true }

    @SuppressLint("MissingPermission") // The activity checks RECORD_AUDIO before requesting projection consent.
    suspend fun capture(onRecording: () -> Unit, onSaving: () -> Unit): RecordedClip {
        val video = File.createTempFile("video-", ".mp4", context.cacheDir)
        val pcm = File.createTempFile("audio-", ".pcm", context.cacheDir)
        val partial = File(AndroidClipRepository.directory(context), "Copig-${UUID.randomUUID()}.part")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        var audio: AudioRecord? = null
        var display: VirtualDisplay? = null
        var recording = false
        var silent = true
        try {
            if (context.filesDir.usableSpace < 256_000_000L) throw IOException("Free at least 256 MB before recording")
            // ponytail: fixed canvas; rotation is letterboxed. Resize the encoding surface if this becomes limiting.
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = maxOf(2, (metrics.widthPixels * scale).toInt() / 2 * 2)
            val height = maxOf(2, (metrics.heightPixels * scale).toInt() / 2 * 2)
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOutputFile(video.absolutePath)
                prepare()
            }
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val bufferSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (bufferSize <= 0) throw IOException("Playback audio is unavailable on this device")
            val input = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(bufferSize * 2, 16384)).build()
            audio = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "Playback audio could not initialize" }
            check(!stop) { "Capture was cancelled before recording started" }
            display = projection.createVirtualDisplay("Copig", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null)
            val videoStart = SystemClock.elapsedRealtimeNanos()
            recorder.start()
            recording = true
            input.startRecording()
            val offsetUs = maxOf(0, (SystemClock.elapsedRealtimeNanos() - videoStart) / 1000)
            onRecording()
            val buffer = ByteArray(8192)
            val deadline = SystemClock.elapsedRealtime() + 15 * 60_000
            pcm.outputStream().use { output ->
                while (!stop && SystemClock.elapsedRealtime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) {
                        // Projection revocation and its callback can race the final audio read.
                        delay(20)
                        if (stop) break
                        throw IOException("Playback audio stopped (code $count)")
                    }
                    if (count == 0) { delay(10); continue }
                    output.write(buffer, 0, count)
                    if (PcmAudio.audible(buffer, count)) silent = false
                    if (context.filesDir.usableSpace < 128_000_000L) break
                }
            }
            onSaving()
            input.stop()
            input.release()
            audio = null
            recorder.stop()
            recording = false
            display.release()
            display = null
            projection.stop()
            combine(video, pcm, partial, offsetUs)
            val clip = File(partial.parentFile, partial.name.removeSuffix(".part") + ".mp4")
            if (!partial.renameTo(clip)) throw IOException("Could not finalize recording")
            return RecordedClip(clip, silent)
        } finally {
            audio?.let { runCatching { it.stop() }; it.release() }
            if (recording) runCatching { recorder.stop() }
            recorder.release()
            display?.release()
            runCatching { projection.stop() }
            video.delete(); pcm.delete(); partial.delete()
        }
    }

    private suspend fun combine(video: File, pcm: File, output: File, offsetUs: Long) {
        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxStarted = false
        try {
            extractor.setDataSource(video.absolutePath)
            val sourceVideo = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: throw IOException("No video frames recorded. Try a longer clip.")
            extractor.selectTrack(sourceVideo)
            val videoFormat = extractor.getTrackFormat(sourceVideo)
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
            if (pcm.length() < 2) throw IOException("No playback audio captured. Import a recording instead.")
            val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder = codec
            val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            }
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); codecStarted = true
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val videoTrack = writer.addTrack(videoFormat)
            var audioTrack = -1
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var samples = 0L
            var progressDeadline = SystemClock.elapsedRealtime() + 15_000
            val bytes = ByteArray(8192)
            pcm.inputStream().use { input ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    var progress = false
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                            val count = input.read(bytes, 0, minOf(bytes.size, buffer.remaining()) and -2)
                            val timeUs = PcmAudio.timeUs(samples, RATE, offsetUs)
                            if (count < 0 || timeUs >= durationUs) {
                                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(bytes, 0, count)
                                codec.queueInputBuffer(index, 0, count, timeUs, 0)
                                samples += count / 2
                            }
                            progress = true
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxStarted) { "Unexpected audio format change" }
                        audioTrack = writer.addTrack(codec.outputFormat)
                        writer.start(); muxStarted = true
                        val capacity = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4_000_000
                        val frame = ByteBuffer.allocate(maxOf(capacity, 4_000_000))
                        val videoInfo = MediaCodec.BufferInfo()
                        val firstTime = extractor.sampleTime
                        check(firstTime >= 0) { "No video samples recorded" }
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            frame.clear()
                            val size = extractor.readSampleData(frame, 0)
                            if (size < 0) break
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            videoInfo.set(0, size, maxOf(0, extractor.sampleTime - firstTime), flags)
                            writer.writeSampleData(videoTrack, frame, videoInfo)
                            extractor.advance()
                        }
                        progress = true
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxStarted) { "Audio encoder output has no format" }
                                writer.writeSampleData(audioTrack, requireNotNull(codec.getOutputBuffer(index)), info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        progress = true
                    }
                    if (progress) progressDeadline = SystemClock.elapsedRealtime() + 15_000
                    else if (SystemClock.elapsedRealtime() > progressDeadline) throw IOException("Audio encoding stalled")
                }
            }
            check(muxStarted) { "Could not encode audio" }
            writer.stop(); muxStarted = false
        } finally {
            extractor.release()
            encoder?.let { if (codecStarted) runCatching { it.stop() }; it.release() }
            muxer?.let { if (muxStarted) runCatching { it.stop() }; it.release() }
        }
    }
    companion object { private const val RATE = 44100 }
}
