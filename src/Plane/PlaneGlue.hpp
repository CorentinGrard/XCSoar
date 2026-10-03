// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

struct Plane;
struct ComputerSettings;
class GlidePolar;
class ProfileMap;

namespace PolarStore { struct Item; }

namespace PlaneGlue {

/**
 * Give the plane one of XCSoar's built-in polars, with the masses,
 * ballast, wing area, speed and handicap that come with it.
 */
void
ApplyPolar(Plane &plane, const PolarStore::Item &item) noexcept;

void
FromProfile(Plane &plane, const ProfileMap &profile) noexcept;

void
Synchronize(const Plane &plane, ComputerSettings &settings,
            GlidePolar &gp) noexcept;

} // namespace PlaneGlue
