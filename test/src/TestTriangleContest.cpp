// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * TriangleContest resumes a branch and bound search over several calls;
 * when Trace::Thin() deletes points in between, it must start over
 * instead of using the deleted points (a use-after-free AddressSanitizer
 * found while replaying flights with Replay::ProcessAllFixes()).
 *
 * The flight goes into a trace like TraceComputer's contest trace, which
 * thins often, and the solver gets one bounded step after every fix, as
 * the idle calculations do; its search spans several steps, so the
 * trace thins under a running search.  Without the fix this reads freed
 * memory: AddressSanitizer reports it (SANITIZE=address), a plain build
 * may not notice.
 */

#include "Engine/Contest/Solvers/OLCFAI.hpp"
#include "Engine/PathSolvers/SolverResult.hpp"
#include "Engine/Trace/Trace.hpp"
#include "IGC/IGCParser.hpp"
#include "IGC/IGCFix.hpp"
#include "IGC/IGCExtensions.hpp"
#include "io/FileLineReader.hpp"
#include "system/Path.hpp"
#include "time/Stamp.hpp"
#include "TestUtil.hpp"
#include "util/PrintException.hxx"

#include <chrono>

using namespace std::chrono;

static void
TestThinWhileSearching(Path path, unsigned trace_size)
{
  /* like TraceComputer's contest trace: no "no thin" time */
  Trace trace({}, Trace::null_time, trace_size);
  /* as ContestComputer runs it */
  OLCFAI solver(trace, true);
  solver.SetIncremental(true);

  FileLineReaderA reader(path);
  IGCExtensions extensions;
  extensions.clear();

  /* steps after which the trace was thinned before the next one */
  unsigned thinned = 0;
  Serial modified = trace.GetModifySerial();

  char *line;
  while ((line = reader.ReadLine()) != nullptr) {
    IGCFix fix;
    if (!IGCParseFix(line, extensions, fix) || !fix.gps_valid)
      continue;

    const TimeStamp time{fix.time.DurationSinceMidnight()};
    trace.push_back(TracePoint(fix.location, time.Cast<duration<unsigned>>(),
                               fix.gps_altitude, 0, 0));

    if (trace.GetModifySerial() != modified)
      ++thinned;

    solver.Solve(false);
    modified = trace.GetModifySerial();
  }

  ok(thinned > 10, "the trace thinned between the solver's steps");

  /* to the end: a result on the points the trace still has */
  while (solver.Solve(true) == SolverResult::INCOMPLETE) {}
  const auto &result = solver.GetBestResult();
  ok(result.distance >= 0 && result.score >= 0, "a sane result");
}

int
main()
try {
  plan_tests(2);
  /* the golden replay where AddressSanitizer found it; TraceComputer's
     contest trace size */
  TestThinWhileSearching(Path("test/data/01lz1hq1.igc"), 256);
  return exit_status();
} catch (...) {
  PrintException(std::current_exception());
  return EXIT_FAILURE;
}
