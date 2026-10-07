// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * Profiles without the UI: what XCSoar's profile list
 * (src/Dialogs/ProfileListDialog.cpp) does.  Profiles are XCSoar's .prf
 * files; the core runs with one, chosen when it is created.  Everything
 * runs on the core main thread.
 */
namespace CoreProfiles {

/**
 * The profiles as JSON (UTF-8), sorted by name:
 *
 *   [{"name": "default", "path": "/…/profiles/default.prf",
 *     "current": true, "password": false}, ...]
 *
 * "password": the profile asks for a password before it is used,
 * copied or deleted (XCSoar's "Password" key).
 */
std::string
List() noexcept;

/** The profile at @p path is unprotected, or @p password is its own
    (ignoring case, like XCSoar). */
bool
CheckPassword(const char *path, const char *password) noexcept;

/**
 * Create profiles/<name>.prf: empty (XCSoar's defaults) or a copy of
 * @p copy_from (the current profile is saved first).
 *
 * @return the new file's path; empty if the name is not a file name,
 * the file exists or it cannot be written
 */
std::string
Create(const char *name, const char *copy_from) noexcept;

/** Delete a profile file; never the current one. */
bool
Delete(const char *path) noexcept;

} // namespace CoreProfiles
