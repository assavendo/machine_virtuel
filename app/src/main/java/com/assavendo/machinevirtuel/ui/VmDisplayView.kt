package com.assavendo.machinevirtuel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.assavendo.machinevirtuel.runtime.RfbClient
import kotlin.math.min

class VmDisplayView(context: Context) : View(context), RfbClient.Listener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gestures = GestureSettingsStore.load(context)
    private var bitmap: Bitmap? = null
    private var client: RfbClient? = null
    private var guestWidth = 1
    private var guestHeight = 1
    private var zoom = 1f
    private var edgeStartX = 0f
    private var edgeCandidate = false
    private var threeFingerCandidate = false
    private var threeFingerMoved = false
    private var threeFingerStartedAt = 0L
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var pointerPressed = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                if (old != zoom) invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom < 1.08f) {
                    zoom = 1f
                    invalidate()
                }
            }
        },
    )

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        requestFocus()
        contentDescription = "Écran de la machine virtuelle"
    }

    fun connect(port: Int) {
        client?.close()
        client = RfbClient("127.0.0.1", port, this).also { it.connect() }
    }

    fun disconnect() {
        client?.close()
        client = null
        pointerPressed = false
    }

    override fun onSize(width: Int, height: Int) {
        require(width in 1..8192 && height in 1..8192 &&
            width.toLong() * height.toLong() <= 16_777_216L
        ) { "La résolution de l'écran invité dépasse la limite mémoire autorisée." }
        post {
            val next = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLACK)
                }
            } catch (t: Throwable) {
                onError(IllegalStateException("Mémoire insuffisante pour l'image de la VM.", t))
                return@post
            }
            bitmap?.recycle()
            bitmap = next
            guestWidth = width
            guestHeight = height
            invalidate()
        }
    }

    override fun onFrame(x: Int, y: Int, width: Int, height: Int, argb: IntArray) {
        post {
            val target = bitmap ?: return@post
            if (x < 0 || y < 0 || x >= target.width || y >= target.height || width <= 0 || height <= 0) {
                return@post
            }
            val copyWidth = min(width, target.width - x)
            val copyHeight = min(height, target.height - y)
            if (argb.size < width * height) return@post
            target.setPixels(argb, 0, width, x, y, copyWidth, copyHeight)
            invalidate()
        }
    }

    override fun onError(error: Throwable) {
        post {
            setBackgroundColor(Color.rgb(55, 35, 35))
            Toast.makeText(context, "Connexion écran VM interrompue: " + (error.message ?: "erreur"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val fitScale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val drawScale = fitScale * zoom
        val drawWidth = image.width * drawScale
        val drawHeight = image.height * drawScale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(image, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = client ?: return true
        val now = SystemClock.uptimeMillis()
        val point = guestPoint(event.x, event.y)

        if (gestures.enabled && gestures.pinchEnabled) {
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeStartX = event.x
                edgeCandidate = gestures.enabled && gestures.edgeSwipeEnabled &&
                    event.x <= dp(28f)
                threeFingerCandidate = false
                threeFingerMoved = false
                if (!edgeCandidate) {
                    current.pointer(1, point.first, point.second)
                    pointerPressed = true
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2 && gestures.enabled && gestures.pinchEnabled) {
                    if (pointerPressed) {
                        current.pointer(0, point.first, point.second)
                        pointerPressed = false
                    }
                }
                if (event.pointerCount == 3 && gestures.enabled && gestures.threeFingerTapEnabled) {
                    threeFingerCandidate = true
                    threeFingerMoved = false
                    threeFingerStartedAt = now
                    val center = centroid(event)
                    threeFingerStartX = center.first
                    threeFingerStartY = center.second
                    if (pointerPressed) {
                        current.pointer(0, point.first, point.second)
                        pointerPressed = false
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (threeFingerCandidate && event.pointerCount >= 3) {
                    val center = centroid(event)
                    if (distance(center.first, center.second, threeFingerStartX, threeFingerStartY) > dp(24f)) {
                        threeFingerMoved = true
                    }
                    return true
                }
                if (edgeCandidate) return true
                if (event.pointerCount > 1 && gestures.enabled && gestures.pinchEnabled) return true
                if (pointerPressed) current.pointer(1, point.first, point.second)
                else current.pointer(0, point.first, point.second)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (threeFingerCandidate) return true
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edgeCandidate) {
                    val swipe = event.x - edgeStartX
                    edgeCandidate = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && swipe >= dp(88f) && now - event.downTime <= 1_200L) {
                        showSoftKeyboard()
                    }
                    return true
                }
                if (threeFingerCandidate) {
                    val shouldSend = event.actionMasked == MotionEvent.ACTION_UP &&
                        !threeFingerMoved && now - threeFingerStartedAt <= 650L
                    threeFingerCandidate = false
                    if (shouldSend) sendCtrlAltDelete(current)
                    return true
                }
                if (pointerPressed) {
                    current.pointer(0, point.first, point.second)
                    pointerPressed = false
                }
                return true
            }
        }
        return true
    }

    private fun showSoftKeyboard() {
        requestFocus()
        post {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendCtrlAltDelete(target: RfbClient) {
        val ctrl = 0xffe3
        val alt = 0xffe9
        val delete = 0xffff
        target.key(true, ctrl)
        target.key(true, alt)
        target.key(true, delete)
        target.key(false, delete)
        target.key(false, alt)
        target.key(false, ctrl)
    }

    private fun guestPoint(x: Float, y: Float): Pair<Int, Int> {
        val image = bitmap
        if (image == null || width <= 0 || height <= 0) return 0 to 0
        val fitScale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val drawScale = fitScale * zoom
        val drawWidth = image.width * drawScale
        val drawHeight = image.height * drawScale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        val guestX = ((x - left) / drawWidth * guestWidth).toInt().coerceIn(0, guestWidth - 1)
        val guestY = ((y - top) / drawHeight * guestHeight).toInt().coerceIn(0, guestHeight - 1)
        return guestX to guestY
    }

    private fun centroid(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until event.pointerCount) {
            x += event.getX(i)
            y += event.getY(i)
        }
        return x / event.pointerCount to y / event.pointerCount
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
