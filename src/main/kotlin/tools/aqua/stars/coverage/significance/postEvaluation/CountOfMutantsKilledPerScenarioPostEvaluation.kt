package tools.aqua.stars.coverage.significance.postEvaluation

import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

object CountOfMutantsKilledPerScenarioPostEvaluation {
  fun evaluate(
    allTSCInstances: List<UUID>,
    randomTrafficTSCInstances: List<UUID>,
    failedMutantsMapping: List<MutantFailure>,
    mutantsToConsider: List<UUID>
  ) {

    val longtail = allTSCInstances.map { it to randomTrafficTSCInstances.count { t -> t == it } }.sortedByDescending { it.second }

    val values : List<Triple<UUID, Int, Int>> = longtail.map { l ->
      Triple(
        l.first,
        l.second,
        failedMutantsMapping.filter { it.startingScenario == l.first }.map { it.mutantID }.filter { it in mutantsToConsider }.toSet().size
      )
    }

    val path: Path = Path.of(
      POST_EVALUATION_BASE_DIR,
      "count_of_mutants_killed_per_scenario",
      "countOfMutantsKilledPerScenario.csv",)
    Files.createDirectories(path.parent)
    path.writeText(values.joinToString (prefix = "Scenario, Frequency in longtail, Count of mutants killed\n", separator = "\n") { "${it.first},${it.second},${it.third}" })
  }
}