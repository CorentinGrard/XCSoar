// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "Engine/Airspace/Ptr.hpp"

#include <string>

/**
 * The dialogs some InfoBoxes open when tapped, without their widgets:
 * the team code dialog (src/Dialogs/Traffic/TeamCodeDialog.cpp), the
 * radio and ATC panels (src/InfoBoxes/Panel/) and the details of the
 * nearest airspace (src/Dialogs/Airspace/dlgAirspaceDetails.cpp).
 * Values are formatted in the pilot's units, as the dialogs show them.
 * Runs on the core main thread.
 */
namespace CoreTilePages {

/**
 * The team code dialog's rows:
 *   {"own": "AB12", "mate": "", "reference": {"id": 3, "name": "Home"},
 *    "range": "12.3 km", "bearing": "123°", "relative_bearing": "12°>",
 *    "flarm_lock": ""}
 * "" where the dialog shows nothing; no "reference" without one.
 */
std::string
DescribeTeamCode() noexcept;

/** The team mate's code ("" forgets it), like "Set code"; false
    unless it is up to 7 letters and digits. */
bool
SetTeamMateCode(const char *code) noexcept;

/** The waypoint team codes count from (-1: none), saved in the
    profile; false for an unknown waypoint. */
bool
SetTeamReference(int waypoint_id) noexcept;

/**
 * The radio frequencies XCSoar sends to radios:
 *   {"active": {"frequency": "122.500", "name": "Tower"},
 *    "standby": {"frequency": "", "name": ""}}
 */
std::string
DescribeRadio() noexcept;

/** Set a frequency ("122.5", MHz) and its name; false if @p mhz is not
    a VHF voice channel. */
bool
SetRadioFrequency(bool standby, const char *mhz, const char *name) noexcept;

/** Move a frequency by @p khz, like the radio panel's buttons; nothing
    happens without a frequency or beyond the band. */
void
OffsetRadioFrequency(bool standby, int khz) noexcept;

/**
 * The ATC reference panels:
 *   {"waypoint": "Home", "location": "45°12'N ...", "declination": 2}
 * "waypoint" is the one within 100 m of the reference ("" if none);
 * no "location" without a reference.
 */
std::string
DescribeAtc() noexcept;

/** Move the ATC reference to a waypoint (-1 clears it); false for an
    unknown waypoint. */
bool
SetAtcReference(int waypoint_id) noexcept;

/** The magnetic declination, -30..30 degrees (east positive); false
    beyond. */
bool
SetMagneticDeclination(double degrees) noexcept;

/** For the dlgAirspaceDetails() seam: the airspace a tile asked for. */
void
ShowAirspace(ConstAirspacePtr airspace) noexcept;

/**
 * The details of the airspace a tile asked for, "{}" if none:
 *   {"name": "CTR Montpellier", "class": "D", "type": "CTR",
 *    "top": "FL 65", "base": "GND", "frequency": "120.505",
 *    "station": "Montpellier Approach", "squawk": "",
 *    "distance": "1.2 km", "acknowledged": false}
 * "distance" is "" without a GPS fix.
 */
std::string
DescribeAirspace() noexcept;

/** "Ack Day" (true) or "Enable" (false) that airspace; false if there
    is none. */
bool
AcknowledgeAirspaceDay(bool acknowledge) noexcept;

} // namespace CoreTilePages
