// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import java.io.File

/** core/api/xcsoar_core.h, so tests can check Kotlin constants against it. */
object CoreHeader {
    val text: String by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core/api/xcsoar_core.h").exists())
            dir = dir.parentFile
        File(dir ?: error("xcsoar_core.h not found"), "core/api/xcsoar_core.h").readText()
    }

    /** NAME = value, and NAME = 1u << bit, from the header. */
    fun constants(prefix: String): Map<String, Int> {
        val plain = Regex("""\b($prefix\w+)\s*=\s*(\d+)\s*,""")
        val shifted = Regex("""\b($prefix\w+)\s*=\s*1u\s*<<\s*(\d+)\s*,""")
        return plain.findAll(text).associate { it.groupValues[1] to it.groupValues[2].toInt() } +
            shifted.findAll(text).associate { it.groupValues[1] to (1 shl it.groupValues[2].toInt()) }
    }
}
