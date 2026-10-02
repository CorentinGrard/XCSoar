// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Prints XCSoar's unit table and what its Formatter makes of a set of
 * values, for the app's own formatting (mobile/docs/DECISIONS.md D9).
 * The output is checked in as
 * mobile/core/src/test/resources/format-golden.txt; the Kotlin test
 * formats the same values and must agree.  "make core-check" fails when
 * this program's output differs from the checked-in file.
 *
 * Lines:
 *   unit <code> <factor> <offset> <name>
 *   <function> <unit code> <SI value> <flag> <output>
 * (the output may contain spaces; it ends the line)
 */

#include "Formatter/Units.hpp"
#include "Units/Descriptor.hpp"
#include "Units/Units.hpp"
#include "Units/Unit.hpp"
#include "Atmosphere/Pressure.hpp"

#include <cstdio>
#include <initializer_list>

static constexpr double values[] = {
  0, 0.04, -0.04, 0.05, -0.05, 0.25, 0.5, 1, 1.5, 2.5, 7.3, -7.3, 12.345,
  27.78, 49.99, 99.4, 99.95, 100, 249.5, 999, 1500, 2499, 2501, 5000,
  12345.6, 99999, 123456, -2.5, -0.5, -150.4,
};

static void
Print(const char *function, Unit unit, double value, int flag,
      const char *output)
{
  std::printf("%s %u %.17g %d %s\n",
              function, unsigned(unit), value, flag, output);
}

int
main()
{
  for (unsigned i = 1; i < unsigned(Unit::COUNT); ++i) {
    const auto &d = Units::unit_descriptors[i];
    std::printf("unit %u %.17g %.17g %s\n",
                i, d.factor_to_user, d.offset_to_user, d.name);
  }

  char buffer[64];

  for (const auto unit : {Unit::METER, Unit::FEET})
    for (const double v : values) {
      FormatAltitude(buffer, v, unit, false);
      Print("altitude", unit, v, 0, buffer);
      FormatRelativeAltitude(buffer, v, unit, false);
      Print("relative_altitude", unit, v, 0, buffer);
    }

  for (const auto unit : {Unit::KILOMETER, Unit::NAUTICAL_MILES,
                          Unit::STATUTE_MILES})
    for (const double v : values) {
      FormatDistanceSmart(buffer, v, unit, true);
      Print("distance_smart", unit, v, 0, buffer);
    }

  for (const auto unit : {Unit::KILOMETER_PER_HOUR, Unit::KNOTS,
                          Unit::STATUTE_MILES_PER_HOUR,
                          Unit::METER_PER_SECOND})
    for (const double v : values)
      for (const bool precision : {false, true}) {
        FormatSpeed(buffer, v, unit, false, precision);
        Print("speed", unit, v, precision, buffer);
      }

  for (const auto unit : {Unit::METER_PER_SECOND, Unit::KNOTS,
                          Unit::FEET_PER_MINUTE})
    for (const double v : values)
      for (const bool sign : {false, true}) {
        FormatVerticalSpeed(buffer, v, unit, false, sign);
        Print("vertical_speed", unit, v, sign, buffer);
      }

  for (const auto unit : {Unit::KG_PER_M2, Unit::LB_PER_FT2})
    for (const double v : values) {
      FormatWingLoading(buffer, sizeof(buffer), v, unit, false);
      Print("wing_loading", unit, v, 0, buffer);
    }

  for (const auto unit : {Unit::KG, Unit::LB})
    for (const double v : values) {
      FormatMass(buffer, v, unit, false);
      Print("mass", unit, v, 0, buffer);
    }

  for (const auto unit : {Unit::DEGREES_CELCIUS, Unit::DEGREES_FAHRENHEIT})
    for (const double v : {253.15, 273.15, 288.15, 300.4, 310.0}) {
      FormatTemperature(buffer, v, unit, false);
      Print("temperature", unit, v, 0, buffer);
    }

  for (const auto unit : {Unit::HECTOPASCAL, Unit::MILLIBAR,
                          Unit::INCH_MERCURY})
    for (const double v : {950.0, 1013.25, 1020.4, 1033.0}) {
      FormatPressure(buffer, AtmosphericPressure::HectoPascal(v), unit,
                     false);
      Print("pressure", unit, v, 0, buffer);
    }

  return 0;
}
