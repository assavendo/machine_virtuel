package com.assavendo.machinevirtuel.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.assavendo.machinevirtuel.runtime.RfbClient

class VmDisplayView(context: Context) : View(context), RfbClient.Listener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bitmap: Bitmap? = null
    private var client: RfbClient? = null
    private var guestWidth = 1
    private var guestHeight = 1

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        requestFocus()
    }

    fun connect(port: Int) {
        client?.close()
        client = RfbClient("127.0.0.1", port, this).also { it.connect() }
    }

    fun disconnect() {
        client?.close()
        client = null
    }

    override fun onSize(width: Int, height: Int) {
        guestWidth = width.coerceAtLeast(1)
        guestHeight = height.coerceAtLeast(1)
        bitmap = Bitmap.createBitmap(guestWidth, guestHeight, Bitmap.Config.ARGB_8888)
        bitmap?.eraseColor(Color.BLACK)
        postInvalidate()
    }

    override fun onFrame(x: Int, y: Int, width: Int, height: Int, argb: IntArray) {
        post {
            val target = bitmap ?: return@post
            if (x >= target.width || y >= target.height) return@post
            val copyWidth = minOf(width, target.width - x)
            val copyHeight = minOf(height, target.height - y)
            target.setPixels(argb, 0, width, x, y, copyWidth, copyHeight)
            invalidate()
        }
    }

    override fun onError(error: Throwable) {
        post { setBackgroundColor(Color.rgb(55, 55, 55)) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        canvas.drawBitmap(
            image,
            null,
            android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
            paint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = client ?: return true
        val x = (event.x / width.coerceAtLeast(1) * guestWidth).toInt().coerceIn(0, guestWidth - 1)
        val y = (event.y / height.coerceAtLeast(1) * guestHeight).toInt().coerceIn(0, guestHeight - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current.pointer(1, x, y)
            MotionEvent.ACTION_MOVE -> current.pointer(0, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current.pointer(0, x, y)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val keysym = keySym(event)
        if (keysym != 0) {
            client?.key(true, keysym)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val keysym = keySym(event)
        if (keysym != 0) {
            client?.key(false, keysym)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun keySym(event: android.view.KeyEvent): Int {
        val unicode = event.unicodeChar
        if (unicode in 32..126) return unicode
        return when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_ENTER -> 0xff0d
            android.view.KeyEvent.KEYCODE_DEL -> 0xff08
            android.view.KeyEvent.KEYCODE_TAB -> 0xff09
            android.view.KeyEvent.KEYCODE_ESCAPE -> 0xff1b
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            android.view.KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
            android.view.KeyEvent.KEYCODE_MOVE_END -> 0xff57
            android.view.KeyEvent.KEYCODE_PAGE_UP -> 0xff55
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
            android.view.KeyEvent.KEYCODE_INSERT -> 0xff63
            android.view.KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe1
            android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe3
            android.view.KeyEvent.KEYCODE_ALT_LEFT,
            android.view.KeyEvent.KEYCODE_ALT_RIGHT -> 0xffe9
            else -> 0
        }
    }
}
