package app.copy.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.copy.CopyApplication
import app.copy.R
import app.copy.domain.CapturePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI lifetime is separate from the recorder; hiding controls never discards a recording. */
object FloatingControls {
    private val shown = MutableStateFlow(false)
    val visible = shown.asStateFlow()
    private fun preferences(context: Context) = context.getSharedPreferences("floating_controls", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", true)
    fun show(context: Context) {
        check(Settings.canDrawOverlays(context)) { "Allow Copig to display over other apps first." }
        context.startForegroundService(Intent(context, FloatingControlsService::class.java))
        preferences(context).edit().putBoolean("enabled", true).apply()
    }
    fun hide(context: Context) {
        val capture = (context.applicationContext as CopyApplication).container.capture
        if (capture.phase.value in listOf(CapturePhase.Ready, CapturePhase.Preparing)) capture.stop()
        preferences(context).edit().putBoolean("enabled", false).apply()
        context.stopService(Intent(context, FloatingControlsService::class.java))
    }
    internal fun setVisible(value: Boolean) { shown.value = value }
}

/** A small Compose window hosted by Android's supported overlay API. */
class FloatingControlsService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry
    private val capture get() = (application as CopyApplication).container.capture
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: ComposeView? = null
    private lateinit var position: WindowManager.LayoutParams
    private var expanded by mutableStateOf(false)
    private var dockRight = true
    private var dragging by mutableStateOf(false)
    private var anchorY = 0

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HIDE) { FloatingControls.hide(this); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification())
            check(Settings.canDrawOverlays(this)) { getString(R.string.overlay_permission_reason) }
            if (overlay == null) addWindow()
        } catch (error: RuntimeException) {
            capture.reportMessage("Floating controls unavailable: ${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun addWindow() {
        val metrics = resources.displayMetrics
        anchorY = metrics.heightPixels / 3
        position = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (metrics.widthPixels - 48 * metrics.density).toInt().coerceAtLeast(0)
            y = anchorY
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingControlsService)
            setViewTreeSavedStateRegistryOwner(this@FloatingControlsService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val phase by capture.phase.collectAsStateWithLifecycle()
                val requesting by capture.requestingConsent.collectAsStateWithLifecycle()
                val message by capture.message.collectAsStateWithLifecycle()
                LaunchedEffect(phase) {
                    // Consent only prepares capture. Keep Record now visible until the user is ready.
                    expanded = phase == CapturePhase.Ready
                }
                LaunchedEffect(expanded, phase, requesting, message, dragging) {
                    if (expanded && phase == CapturePhase.Idle && !requesting && !dragging) {
                        delay(if (message == null) 8_000 else 15_000)
                        expanded = false
                    }
                }
                CopyTheme {
                    FloatingRecorder(phase, requesting, message, expanded,
                        onExpand = { expanded = true }, onCollapse = { expanded = false },
                        onRecord = {
                            if (phase == CapturePhase.Ready) { expanded = false; capture.record() }
                            else CapturePermissionActivity.launch(this@FloatingControlsService)
                        },
                        onStop = { expanded = false; capture.stop() }, onDrag = ::moveWindow,
                        onDragStart = { dragging = true }, onDragEnd = ::finishDrag)
                }
            }
            // A tap in the video closes the panel and still reaches the app underneath.
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) expanded = false
                false
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitWindow()
            }
        }
        overlay = view
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windows.addView(view, position)
        FloatingControls.setVisible(true)
    }
    private fun moveWindow(dx: Float, dy: Float) {
        position.x = (position.x + dx).toInt()
        anchorY = (position.y + dy).toInt()
        fitWindow()
    }
    private fun viewport(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.width() - insets.left - insets.right) to
                (metrics.bounds.height() - insets.top - insets.bottom)
        }
        return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }
    private fun finishDrag() {
        val view = overlay ?: return
        dockRight = position.x > (viewport().first - view.width).coerceAtLeast(0) / 2
        dragging = false
        fitWindow()
    }
    private fun fitWindow() {
        val view = overlay ?: return
        if (!view.isAttachedToWindow || view.width == 0) return
        val (width, height) = viewport()
        val fitted = fitOverlay(position.x, anchorY, view.width, view.height, width, height,
            if (dragging) null else dockRight)
        position.x = fitted.x
        position.y = fitted.y
        try { windows.updateViewLayout(view, position) }
        catch (_: RuntimeException) { stopSelf() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.post { fitWindow() }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hide = PendingIntent.getService(this, 11, Intent(this, FloatingControlsService::class.java).setAction(HIDE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_pig)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.floating_notification))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.hide_floating), hide).build()).build()
    }
    override fun onDestroy() {
        if (capture.phase.value in listOf(CapturePhase.Ready, CapturePhase.Preparing)) capture.stop()
        overlay?.let { view ->
            if (view.isAttachedToWindow) runCatching { windows.removeView(view) }
            view.disposeComposition()
        }
        overlay = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        FloatingControls.setVisible(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "floating_controls"
        private const val NOTIFICATION_ID = 2
        private const val HIDE = "app.copy.HIDE_FLOATING"
    }
}

@Composable
private fun FloatingRecorder(
    phase: CapturePhase, requesting: Boolean, message: String?, expanded: Boolean,
    onExpand: () -> Unit, onCollapse: () -> Unit, onRecord: () -> Unit, onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit, onDragStart: () -> Unit, onDragEnd: () -> Unit,
) {
    val status = if (requesting) stringResource(R.string.approve_capture)
        else if (phase == CapturePhase.Idle && message != null) message
        else stringResource(when (phase) {
            CapturePhase.Idle -> R.string.floating_idle
            CapturePhase.Preparing -> R.string.preparing
            CapturePhase.Ready -> R.string.floating_ready
            CapturePhase.Recording -> R.string.recording
            CapturePhase.Saving -> R.string.saving
        })
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragStart = { onDragStart() }, onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, amount ->
            change.consume(); onDrag(amount.x, amount.y)
        }
    }
    if (!expanded) {
        val description = stringResource(R.string.open_floating)
        Surface(onClick = onExpand, modifier = Modifier.size(48.dp).then(drag)
            .semantics { contentDescription = description; stateDescription = status }, shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp) {
            Box(contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(68.dp))
                if (requesting || phase != CapturePhase.Idle) {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(
                        if (phase == CapturePhase.Recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
        return
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp, tonalElevation = 3.dp) {
        Column(Modifier.width(232.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), stringResource(R.string.drag_pig),
                    Modifier.size(48.dp).then(drag))
                val stopping = phase == CapturePhase.Recording
                Button(onClick = if (stopping) onStop else onRecord, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    enabled = !requesting && phase in listOf(CapturePhase.Idle, CapturePhase.Ready, CapturePhase.Recording)) {
                    Text(stringResource(if (stopping) R.string.floating_stop else if (phase == CapturePhase.Ready) R.string.record_now else R.string.floating_record))
                }
                val collapseDescription = stringResource(R.string.collapse_floating)
                TextButton(onClick = onCollapse, modifier = Modifier.size(48.dp).semantics { contentDescription = collapseDescription }) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp))
            if (phase == CapturePhase.Ready) TextButton(onClick = onStop) { Text(stringResource(R.string.cancel_setup)) }
        }
    }
}
