package com.watchlinuxinput

object InputProtocol {
    const val PORT = 9877

    // Packet types
    const val TYPE_HEARTBEAT: Byte = 0x00
    const val TYPE_KEY: Byte = 0x01
    const val TYPE_GESTURE: Byte = 0x02
    const val TYPE_ROTARY: Byte = 0x03

    // Action
    const val ACTION_PRESS: Byte = 0x00

    // Key values
    const val KEY_ESC: Byte = 1
    const val KEY_TAB: Byte = 2
    const val KEY_PLAY_PAUSE: Byte = 3
    const val KEY_PREV: Byte = 4
    const val KEY_NEXT: Byte = 5
    const val KEY_VOL_UP: Byte = 6
    const val KEY_VOL_DOWN: Byte = 7

    // Gesture values
    const val GESTURE_TAP: Byte = 1       // Enter
    const val GESTURE_LEFT: Byte = 3      // Left arrow
    const val GESTURE_RIGHT: Byte = 4     // Right arrow
    const val GESTURE_UP: Byte = 5        // Up arrow
    const val GESTURE_DOWN: Byte = 6      // Down arrow

    // Rotary values
    const val ROTARY_CW: Byte = 1         // Scroll down or vol up
    const val ROTARY_CCW: Byte = 2        // Scroll up or vol down

    fun packet(type: Byte, value: Byte, action: Byte = ACTION_PRESS): ByteArray {
        return byteArrayOf(type, value, action, 0)
    }

    fun heartbeat(): ByteArray {
        return byteArrayOf(TYPE_HEARTBEAT, 0, 0, 0)
    }
}
