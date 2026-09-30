// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#ifdef NDEBUG

static inline void
InitThreadDebug()
{
}

#ifdef ENABLE_OPENGL

static inline void
EnterDrawThread()
{
}

static inline void
LeaveDrawThread()
{
}

#endif

#else /* !NDEBUG */

/**
 * Declare the calling thread as XCSoar's main thread (the one running
 * the UI event loop).  Needed where that is not the thread which
 * started the process (Android, the headless core).
 */
void
InitThreadDebug();

bool
InMainThread();

bool
InDrawThread();

#ifdef ENABLE_OPENGL

/**
 * Marks the current thread as DrawThread.  This is used on OpenGL
 * (which has no DrawThread) to allow using InDrawThread() in
 * assertions.
 */
void
EnterDrawThread();

/**
 * Undo the effect of EnterDrawThread().
 */
void
LeaveDrawThread();

#endif

#endif /* !NDEBUG */
