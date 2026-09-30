package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * What DiPlay does with one steering-wheel key event while CarPlay is on screen.
 *
 * [consume] keeps the key from the car's own player. [mediaIndex] is the CarPlay HID press to send
 * now, if any. [siri] opens Siri on the iPhone. [unknownKeyCode] is a code worth recording once for
 * firmware discovery.
 */
internal data class WheelKeyOutcome(
    val consume: Boolean,
    val mediaIndex: Int? = null,
    val siri: Boolean = false,
    val unknownKeyCode: Int? = null,
)

/**
 * The outcome for one wheel key event.
 *
 * Hardware keys are decided here rather than through a MediaSession: Android only routes recognized
 * `KEYCODE_MEDIA_*` codes to a session, so BYD's vendor codes never reach a `MediaSession.Callback`
 * and would do nothing there. A wheel press arrives as an instant down/up pair and a held key
 * repeats, so only the first `ACTION_DOWN` sends a press.
 *
 * Media keys are consumed even when no controller is attached yet (mid-reconnect): while CarPlay is
 * on screen a press must not fall through to the car's own player, and the logged `sent=false` is
 * the diagnostic for that window.
 */
internal fun wheelKeyOutcome(keyCode: Int, action: Int, repeatCount: Int): WheelKeyOutcome =
    when {
        CarPlayMediaButton.opensSiri(keyCode) -> WheelKeyOutcome(
            consume = true,
            siri = action == KeyEvent.ACTION_UP,
        )
        CarPlayMediaButton.isMediaKey(keyCode) -> WheelKeyOutcome(
            consume = true,
            mediaIndex = if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                CarPlayMediaButton.forKeyCode(keyCode)
            } else {
                null
            },
        )
        CarPlayMediaButton.shouldLogUnknownKey(keyCode) -> WheelKeyOutcome(
            consume = false,
            unknownKeyCode = keyCode,
        )
        else -> WheelKeyOutcome(consume = false)
    }
