/*
 * Copyright 2026 The STARS Coverage Significance Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tools.aqua.stars.coverage.significance.postEvaluation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ReplayAttemptOutcome
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ReplayDeterminismSummary
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.ResumeFailureSummary

/**
 * Renders [ReplayDeterminismAnalysis] (Part A) and [ResumeFailureAggregation] (Part B)'s findings
 * into one Markdown report, built straight from the two summary objects — no hand-maintained prose
 * that could drift from the data.
 */
object ReplayDiagnosticsReport {

  private fun basePath(): Path = Path.of(POST_EVALUATION_BASE_DIR, "replay_diagnostics")

  private fun pct(value: Double): String = "%.2f".format(value * 100)

  /** @return The path the report was written to. */
  fun write(
      runId: Int?,
      ticksPerMutant: Int,
      maxMutants: Int?,
      maxNonMonotonicSample: Int,
      maxLeadTimeSteps: Int,
      determinism: ReplayDeterminismSummary,
      resumeFailure: ResumeFailureSummary?,
  ): Path {
    val successCount = determinism.outcomeCounts[ReplayAttemptOutcome.SUCCESS] ?: 0
    val successFraction =
        if (determinism.totalAttempts == 0) 1.0
        else successCount.toDouble() / determinism.totalAttempts

    val report = buildString {
      appendLine("# Replay Determinism & Resume-Failure Diagnostics Report")
      appendLine()
      appendLine("- Run id: ${runId ?: "all"}")
      appendLine("- Generated: ${Instant.now()}")
      appendLine(
          "- Sampling: ticksPerMutant=$ticksPerMutant, maxMutants=${maxMutants ?: "unbounded"}, " +
              "maxNonMonotonicSample=$maxNonMonotonicSample, maxLeadTimeSteps=$maxLeadTimeSteps")
      appendLine()

      appendLine("## Part A — Determinism check (double-run diff)")
      appendLine()
      appendLine(
          "Sampled ${determinism.totalTicksSampled} ticks across ${determinism.mutantsSampled} mutants, " +
              "${determinism.totalAttempts} total lead-time attempts.")
      appendLine()
      appendLine("| Outcome | Count | % |")
      appendLine("|---|---|---|")
      ReplayAttemptOutcome.entries.forEach { outcome ->
        val count = determinism.outcomeCounts[outcome] ?: 0
        val share =
            if (determinism.totalAttempts == 0) 0.0
            else count.toDouble() / determinism.totalAttempts
        appendLine("| $outcome | $count | ${pct(share)}% |")
      }
      appendLine()

      appendLine("### By sample source")
      appendLine()
      appendLine("| Sample source | Outcome | Count |")
      appendLine("|---|---|---|")
      determinism.outcomeCountsBySampleSource.forEach { (source, counts) ->
        counts.forEach { (outcome, count) -> appendLine("| $source | $outcome | $count |") }
      }
      appendLine()

      appendLine("### By lead-time bucket")
      appendLine()
      appendLine("| Bucket (s) | " + ReplayAttemptOutcome.entries.joinToString(" | ") + " |")
      appendLine("|---" + "|---".repeat(ReplayAttemptOutcome.entries.size) + "|")
      determinism.leadTimeBucketOutcomeCounts.entries
          .sortedBy { it.key }
          .forEach { (bucket, counts) ->
            appendLine(
                "| $bucket | " +
                    ReplayAttemptOutcome.entries.joinToString(" | ") {
                      (counts[it] ?: 0).toString()
                    } +
                    " |")
          }
      appendLine()

      appendLine("### By mutant (only mutants with at least one non-SUCCESS outcome)")
      appendLine()
      val worstMutants =
          determinism.mutantStats.filter { stats ->
            stats.outcomeCounts.any { (outcome, count) ->
              outcome != ReplayAttemptOutcome.SUCCESS && count > 0
            }
          }
      if (worstMutants.isEmpty()) {
        appendLine("None — every sampled mutant's attempts were all SUCCESS.")
      } else {
        appendLine("| Mutant id | Total attempts | Non-SUCCESS outcomes |")
        appendLine("|---|---|---|")
        worstMutants.forEach { stats ->
          val nonSuccess =
              stats.outcomeCounts
                  .filterKeys { it != ReplayAttemptOutcome.SUCCESS }
                  .filterValues { it > 0 }
                  .entries
                  .joinToString(", ") { "${it.key}=${it.value}" }
          appendLine("| ${stats.mutantId} | ${stats.totalAttempts} | $nonSuccess |")
        }
      }
      appendLine()

      appendLine("### Epsilon headroom (over SUCCESS attempts only)")
      appendLine()
      appendLine(
          "Chosen epsilons: position=1mm, speed=1mm/s, acceleration=1cm/s² (see " +
              "ReplayDeterminismAnalysis's KDoc for rationale).")
      appendLine()
      appendLine("| Field | Max abs diff | p99 abs diff | p50 abs diff |")
      appendLine("|---|---|---|---|")
      determinism.epsilonHeadroom.forEach { (field, stats) ->
        appendLine("| $field | ${stats.maxAbsDiff} | ${stats.p99AbsDiff} | ${stats.p50AbsDiff} |")
      }
      appendLine()

      appendLine("### Example tick ids by outcome")
      appendLine()
      if (determinism.exampleTickIdsByOutcome.isEmpty()) {
        appendLine("None — no non-SUCCESS outcomes occurred.")
      } else {
        determinism.exampleTickIdsByOutcome.forEach { (outcome, ids) ->
          appendLine("- **$outcome**: ${ids.joinToString(", ")}")
        }
      }
      appendLine()

      appendLine(
          if (successFraction > 0.99)
              "**Verdict:** the replay mechanism appears deterministic given identical inputs " +
                  "(${pct(successFraction)}% SUCCESS) — consistent with the existing sweep's " +
                  "`fullReplayMismatchCount == 0`."
          else
              "**Verdict:** a non-trivial fraction of attempts (${pct(1 - successFraction)}%) did NOT " +
                  "reproduce identically across two independent replays of the same inputs — see the " +
                  "example tick ids above for manual follow-up.")
      appendLine()

      if (resumeFailure != null) {
        appendLine("## Part B — Resume-from-later-tick failure diagnostics")
        appendLine()
        appendLine(
            "Re-aggregated ${resumeFailure.totalTicksInDetailFiles} ticks from the existing " +
                "g0_full_lead_time_sweep detail files (read-only, no new simulation).")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---|")
        appendLine("| Total lead-time steps analyzed | ${resumeFailure.overall.totalSteps} |")
        appendLine("| Reproducing | ${resumeFailure.overall.reproducingCount} |")
        appendLine("| Diverged | ${resumeFailure.overall.divergedCount} |")
        appendLine(
            "| Inconclusive (resume not possible) | ${resumeFailure.overall.inconclusiveCount} " +
                "(${pct(resumeFailure.overall.inconclusiveFraction)}%) |")
        appendLine(
            "| No real candidate near target lead time | " +
                "${resumeFailure.overall.noRealCandidateNearTargetCount} " +
                "(${pct(resumeFailure.overall.noRealCandidateNearTargetFraction)}%) |")
        if (resumeFailure.totalTicksFlaggedInDb != null) {
          appendLine(
              "| Ticks flagged in DB vs. in detail files | ${resumeFailure.totalTicksFlaggedInDb} vs. " +
                  "${resumeFailure.totalTicksInDetailFiles} (missing: ${resumeFailure.ticksMissingFromDetailFiles}) |")
        }
        appendLine()

        appendLine("### By lead-time bucket")
        appendLine()
        appendLine("| Bucket (s) | Total steps | Inconclusive % | No-real-candidate % |")
        appendLine("|---|---|---|---|")
        resumeFailure.byLeadTimeBucket.entries
            .sortedBy { it.key }
            .forEach { (bucket, b) ->
              appendLine(
                  "| $bucket | ${b.totalSteps} | ${pct(b.inconclusiveFraction)}% | " +
                      "${pct(b.noRealCandidateNearTargetFraction)}% |")
            }
        appendLine()

        appendLine("### By mutant (top 10 by inconclusive fraction)")
        appendLine()
        appendLine("| Mutant id | Total steps | Inconclusive % |")
        appendLine("|---|---|---|")
        resumeFailure.byMutant.entries
            .sortedByDescending { it.value.inconclusiveFraction }
            .take(10)
            .forEach { (mutantId, b) ->
              appendLine("| $mutantId | ${b.totalSteps} | ${pct(b.inconclusiveFraction)}% |")
            }
        appendLine()

        if (resumeFailure.ticksMissingFromDetailFiles != null &&
            resumeFailure.ticksMissingFromDetailFiles != 0) {
          appendLine(
              "**Note:** ${resumeFailure.ticksMissingFromDetailFiles} ticks flagged in the DB are missing " +
                  "from the existing sweep's detail files. This alone cannot distinguish an already-logged " +
                  "\"unknown scenario\" skip from a crashed-worker data loss — check the original sweep's " +
                  "worker stdout logs.")
          appendLine()
        }

        appendLine(
            "**Root cause (restated):** there is no real SUMO state save/load in this project. " +
                "\"Resuming from a later tick\" reconstructs the scene from a recorded snapshot " +
                "(position/lane/speed/type only — no RNG-stream position, no lane-change-model " +
                "accumulator state, no true acceleration) and can only target a tick a prior run " +
                "already recorded for that exact (run, scenario, mutant). The inconclusive/diverged " +
                "rates above, if rising with lead time, are consistent with this reconstruction " +
                "*fidelity* degrading the further back it reaches — not with the replay mechanism " +
                "being nondeterministic (see Part A).")
        appendLine()
      }

      appendLine("## Conclusion")
      appendLine()
      appendLine(
          if (successFraction > 0.99 &&
              resumeFailure != null &&
              resumeFailure.overall.inconclusiveFraction > 0.0)
              "Part A found the replay mechanism deterministic given identical inputs; Part B found a " +
                  "nonzero inconclusive/diverged rate when resuming from later ticks. Together this points " +
                  "at reconstruction *fidelity* (what the hand-rolled scene reconstruction cannot restore), " +
                  "not simulation *nondeterminism*, as the dominant cause of \"can't resume from a later tick\"."
          else
              "See Part A and Part B above for the actual findings of this run; the numbers should be read " +
                  "directly rather than assumed to match the expected pattern.")
    }

    val base = basePath()
    Files.createDirectories(base)
    val reportPath = base.resolve("replay_diagnostics_report_${runId?.toString() ?: "all"}.md")
    reportPath.writeText(report)
    println("Report written to: $reportPath")
    return reportPath
  }
}
