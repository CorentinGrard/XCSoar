// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "Parser.hpp"
#include "FileRepository.hpp"
#include "io/LineReader.hpp"
#include "util/StringStrip.hxx"
#include "util/HexString.hpp"
#include "util/NumberParser.hpp"
#include "system/Path.hpp"

/**
 * Parses a line of the repository file.
 * Each line is of the form `name = value`
 * @returns A pointer to the value field.
 */
static const char *
ParseLine(char *line)
{
  char *separator = strchr(line, '=');
  if (separator == nullptr)
    /* malformed line */
    return nullptr;

  char *p = StripRight(line, separator);
  if (p == line)
    /* empty name */
    return nullptr;

  *p = 0;

  char *value = const_cast<char *>(StripLeft(separator + 1));
  StripRight(value);
  return value;
}

/**
 * Parses a bounding box: "west,south,east,north" in degrees.
 *
 * @return an invalid #GeoBounds if the value is malformed
 */
static GeoBounds
ParseBounds(const char *value) noexcept
{
  double degrees[4];
  for (unsigned i = 0; i < 4; ++i) {
    char *endptr;
    degrees[i] = ParseDouble(value, &endptr);
    if (endptr == value || *endptr != (i < 3 ? ',' : '\0'))
      return GeoBounds::Invalid();

    if (i < 3)
      value = endptr + 1;
  }

  const GeoPoint north_west(Angle::Degrees(degrees[0]),
                            Angle::Degrees(degrees[3]));
  const GeoPoint south_east(Angle::Degrees(degrees[2]),
                            Angle::Degrees(degrees[1]));
  if (!north_west.Check() || !south_east.Check() ||
      degrees[1] > degrees[3])
    return GeoBounds::Invalid();

  return GeoBounds(north_west, south_east);
}

static bool
Commit(FileRepository &repository, AvailableFile &file)
{
  if (file.IsEmpty())
    return true;

  const Path path(file.name.c_str());
  if (!path.IsValidFilename())
    return false;

  if (!file.IsValid())
    return false;

  repository.files.emplace_back(std::move(file));
  file.Clear();
  return true;
}

bool
ParseFileRepository(FileRepository &repository, NLineReader &reader)
{
  AvailableFile file;
  file.Clear();

  char *line;
  while ((line = reader.ReadLine()) != nullptr) {
    line = const_cast<char *>(StripLeft(line));
    if (*line == 0 || *line == '#')
      continue;

    const char *name = line, *value = ParseLine(line);
    if (value == nullptr)
      return false;

    if (StringIsEqual(name, "name")) {
      if (!Commit(repository, file))
        return false;

      file.name.assign(value);
    } else if (file.IsEmpty()) {
      /* ignore */
    } else if (StringIsEqual(name, "uri")) {
      file.uri.assign(value);
    } else if (StringIsEqual(name, "description")) {
      file.description.assign(value);
    } else if (StringIsEqual(name, "area")) {
      file.area = value;
    } else if (StringIsEqual(name, "bbox")) {
      file.bounds = ParseBounds(value);
    } else if (StringIsEqual(name, "type")) {
      if (StringIsEqual(value, "airspace"))
        file.type = FileType::AIRSPACE;
      else if (StringIsEqual(value, "waypoint-details"))
        file.type = FileType::WAYPOINTDETAILS;
      else if (StringIsEqual(value, "waypoint"))
        file.type = FileType::WAYPOINT;
      else if (StringIsEqual(value, "map"))
        file.type = FileType::MAP;
      else if (StringIsEqual(value, "flarmnet"))
        file.type = FileType::FLARMNET;
      else if (StringIsEqual(value, "rasp"))
        file.type = FileType::RASP;
      else if (StringIsEqual(value, "xci"))
        file.type = FileType::XCI;
      else if (StringIsEqual(value, "task"))
        file.type = FileType::TASK;
      else if (StringIsEqual(value, "checklist"))
        file.type = FileType::CHECKLIST;
    } else if (StringIsEqual(name, "update")) {
      unsigned year, month, day;
      if (sscanf(value, "%04u-%02u-%02u", &year, &month, &day) == 3)
        file.update_date = BrokenDate(year, month, day);
    } else if (StringIsEqual(name, "sha256")) {
      try {
        file.sha256_hash = ParseHexString<32>(std::string_view(value));
      } catch (std::exception &e) {
        // Parsing failed, sha256_hash stays zeroed
      }
    }
  }

  return Commit(repository, file);
}
