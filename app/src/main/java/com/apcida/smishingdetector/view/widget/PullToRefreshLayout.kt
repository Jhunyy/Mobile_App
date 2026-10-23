package com.apcida.smishingdetector.view.widget

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.apcida.smishingdetector.R
import kotlin.math.abs

/** A small pull-down refresh container for the app's existing scroll views. */
class PullToRefreshLayout(context: Context) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val triggerDistance = dp(72)
    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(context.getColor(R.color.inbox_green))
        contentDescription = "Refreshing"
        visibility = View.GONE
        elevation = dp(4).toFloat()
    }
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var refreshListener: (() -> Unit)? = null

    /** Return true while the screen's scrollable content is above its top edge. */
    var canChildScrollUp: () -> Boolean = { false }

    var isRefreshing = false
        set(value) {
            field = value
            indicator.visibility = if (value) View.VISIBLE else View.GONE
        }

    init {
        addView(indicator, LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })
    }

    fun setOnRefreshListener(listener: () -> Unit) {
        refreshListener = listener
    }

    fun setContent(content: View) {
        addView(content, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // RecyclerView and NestedScrollView may claim the gesture before the pull reaches us.
        // Keep observing it so we can intercept only when the content is at its top edge.
        if (!disallowIntercept) super.requestDisallowInterceptTouchEvent(false)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (isRefreshing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceY = event.y - startY
                if (!dragging && distanceY > touchSlop && distanceY > abs(event.x - startX) &&
                    !canChildScrollUp()) {
                    dragging = true
                    indicator.visibility = View.VISIBLE
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> resetDrag()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP -> {
                val shouldRefresh = event.y - startY >= triggerDistance
                dragging = false
                if (shouldRefresh && refreshListener != null) {
                    isRefreshing = true
                    refreshListener?.invoke()
                } else {
                    indicator.visibility = View.GONE
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetDrag()
                return true
            }
        }
        return true
    }

    private fun resetDrag() {
        dragging = false
        if (!isRefreshing) indicator.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
