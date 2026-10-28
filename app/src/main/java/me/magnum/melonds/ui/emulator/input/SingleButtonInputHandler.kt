package me.magnum.melonds.ui.emulator.input

import android.view.MotionEvent
import android.view.View
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.domain.model.Input

class SingleButtonInputHandler(inputListener: IInputListener, private val input: Input, enableHapticFeedback: Boolean, touchVibrator: TouchVibrator) : FeedbackInputHandler(inputListener, enableHapticFeedback, touchVibrator) {
    private var isPressed = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isPressed) {
                    isPressed = true
                    inputListener.onKeyPress(input)
                    performHapticFeedback(v, HapticFeedbackType.KEY_PRESS)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isPressed) {
                    isPressed = false
                    inputListener.onKeyReleased(input)
                    performHapticFeedback(v, HapticFeedbackType.KEY_RELEASE)
                }
            }
            // The gesture was interrupted (e.g. by a system gesture or a parent taking over the touch stream). Release the button so that it doesn't remain stuck
            MotionEvent.ACTION_CANCEL -> {
                if (isPressed) {
                    isPressed = false
                    inputListener.onKeyReleased(input)
                }
            }
        }
        return true
    }
}
