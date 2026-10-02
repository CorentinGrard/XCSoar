// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar

/**
 * The one part of the old UI's NativeView that the reused [BitmapUtil]
 * calls; the view itself is not part of the new app (D13).
 */
object NativeView {
    @JvmField
    var textureNonPowerOfTwo = false

    @JvmStatic
    fun validateTextureSize(size: Int): Int {
        if (textureNonPowerOfTwo) return size
        var p = 1
        while (p < size) p = p shl 1
        return p
    }
}
