package io.github.kotoriinsky.tsuzuri.hook.feature.portal

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView

/** 普通纹理层按首帧交接封面，避免 SurfaceView 准备期间的空表面盖住照片。 */
class PortalVideoPreview(context: Context, private val uri: Uri, private val valid: () -> Boolean,
    private val changed: (Boolean, Boolean) -> Unit, private val failed: (Int, Int) -> Unit) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var requested = true
    private var released = false
    private var frame = false
    private val timeout = Runnable { if (!released && !frame) fail(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_TIMED_OUT) }
    val isPlaying: Boolean get() = prepared && !released && runCatching { player?.isPlaying == true }.getOrDefault(false)
    val hasFrame: Boolean get() = frame && !released

    init {
        alpha = 0f
        isOpaque = false
        surfaceTextureListener = this
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || !valid()) { dispose(); return }
        try {
            surface = Surface(texture)
            val media = MediaPlayer()
            player = media
            media.setSurface(surface)
            media.setOnPreparedListener {
                if (!current(it)) return@setOnPreparedListener
                try {
                    prepared = true
                    it.isLooping = true
                    it.setVolume(0f, 0f)
                    fit(it.videoWidth, it.videoHeight)
                    if (requested) it.start()
                    postChanged()
                } catch (_: Exception) { fail(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO) }
            }
            media.setOnVideoSizeChangedListener { source, videoWidth, videoHeight ->
                if (current(source)) fit(videoWidth, videoHeight)
            }
            media.setOnErrorListener { source, what, extra ->
                if (current(source)) fail(what, extra)
                true
            }
            media.setDataSource(context, uri)
            media.prepareAsync()
        } catch (_: Exception) { fail(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO) }
    }
    private fun current(candidate: MediaPlayer): Boolean {
        if (released || candidate !== player) return false
        if (!valid()) { dispose(); return false }
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (released || !valid()) { dispose(); return }
        if (!prepared || frame) return
        // 此事件表示纹理已收到实际帧；onPrepared 只表示可以开始解码。
        frame = true
        alpha = 1f
        main.removeCallbacks(timeout)
        postChanged()
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        player?.let { fit(it.videoWidth, it.videoHeight) }
    }
    private fun fit(videoWidth: Int, videoHeight: Int) {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        })
    }
    fun toggle() {
        if (released || !valid()) { dispose(); return }
        requested = !requested
        try {
            if (prepared) player?.let { if (requested) it.start() else it.pause() }
            main.removeCallbacks(timeout)
            if (requested && !frame) main.postDelayed(timeout, 8000)
            postChanged()
        } catch (_: Exception) { fail(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO) }
    }
    private fun fail(what: Int, extra: Int) {
        if (released) return
        val notify = valid()
        dispose()
        if (notify) main.post { if (valid()) failed(what, extra) }
    }
    private fun postChanged() {
        // 首帧通知可能发生在父容器 dispatchDraw 中；界面回调会增删加载控件，必须等本次绘制返回。
        main.post { if (!released && valid()) changed(isPlaying, frame) }
    }
    fun dispose() {
        if (released) return
        released = true
        alpha = 0f
        frame = false
        main.removeCallbacks(timeout)
        player?.let { media ->
            media.setOnPreparedListener(null)
            media.setOnErrorListener(null)
            media.setOnVideoSizeChangedListener(null)
            runCatching { media.release() }
        }
        player = null
        surface?.release()
        surface = null
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { dispose(); return true }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released) main.postDelayed(timeout, 8000)
    }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
