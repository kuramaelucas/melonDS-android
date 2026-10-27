package me.magnum.melonds.ui.emulator

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import me.magnum.melonds.domain.model.render.PresentFrameWrapper
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import me.magnum.melonds.ui.emulator.render.EmulatorRenderer
import me.magnum.melonds.ui.emulator.render.GlContext
import me.magnum.melonds.ui.emulator.render.SurfaceRenderer

class EmulatorSurfaceView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private companion object {
        const val DS_FRAME_RATE = 60f
        // While no new frames are produced (paused, loading, etc), the surface is still redrawn at this interval so that renderer state changes become visible
        const val IDLE_REDRAW_INTERVAL_NS = 100_000_000L
    }

    private val surfaceLock = Any()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surface: Surface? = null
    private var renderer: EmulatorRenderer? = null
    private var surfaceRenderer: SurfaceRenderer? = null
    @Volatile private var isRedrawRequired = true
    private var lastDrawTimeNs = 0L

    init {
        holder.addCallback(this)
    }

    fun setRenderer(emulatorRenderer: EmulatorRenderer) {
        renderer = emulatorRenderer
        requestRedraw()
    }

    fun updateRendererConfiguration(newRendererConfiguration: RuntimeRendererConfiguration?) {
        renderer?.updateRendererConfiguration(newRendererConfiguration)
        requestRedraw()
    }

    fun requestRedraw() {
        isRedrawRequired = true
    }

    fun setSurfaceRenderer(newRenderer: SurfaceRenderer, glContext: GlContext) {
        synchronized(surfaceLock) {
            surfaceRenderer?.stop(glContext)
            surfaceRenderer = newRenderer
            isRedrawRequired = true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) {
            surface = holder.surface
            isRedrawRequired = true
        }
        setPreferredFrameRate(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(surfaceLock) {
            surfaceWidth = width
            surfaceHeight = height
            isRedrawRequired = true
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) {
            surface = null
            isRedrawRequired = true
        }
    }

    /**
     * The DS runs at ~60 FPS. Let the system know that a higher refresh rate is not needed, allowing high refresh rate displays to switch to 60Hz (only if the switch is
     * seamless), which considerably reduces power usage.
     */
    private fun setPreferredFrameRate(surface: Surface) {
        if (!surface.isValid) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(DS_FRAME_RATE, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(DS_FRAME_RATE, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    /**
     * Draws the surface if required. Redrawing the same emulator frame wastes GPU time and battery (e.g. on high refresh rate displays or while the emulator is paused), so
     * the surface is only redrawn when a new frame is available, when the surface or renderer changes, or periodically while idle.
     *
     * @return Whether the surface was drawn
     */
    fun doFrame(glContext: GlContext, presentFrameWrapper: PresentFrameWrapper): Boolean {
        synchronized(surfaceLock) {
            val now = System.nanoTime()
            if (!presentFrameWrapper.isNewFrame && !isRedrawRequired && now - lastDrawTimeNs < IDLE_REDRAW_INTERVAL_NS) {
                return false
            }

            isRedrawRequired = false
            lastDrawTimeNs = now
            surfaceRenderer?.doFrame(
                glContext = glContext,
                surfaceView = this,
                surface = surface,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                renderer = renderer,
                presentFrameWrapper = presentFrameWrapper,
            )
            return true
        }
    }

    fun stop(glContext: GlContext) {
        synchronized(surfaceLock) {
            surfaceRenderer?.stop(glContext)
        }
    }
}
