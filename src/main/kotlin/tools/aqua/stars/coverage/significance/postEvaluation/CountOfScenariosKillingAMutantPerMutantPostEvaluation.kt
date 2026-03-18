package tools.aqua.stars.coverage.significance.postEvaluation

import tools.aqua.stars.coverage.significance.POST_EVALUATION_BASE_DIR
import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

object CountOfScenariosKillingAMutantPerMutantPostEvaluation {
  fun evaluate(scenarioKillingMutantPerMutant : List<Pair<UUID, Int>>) {
    val path: Path = Path.of(
      POST_EVALUATION_BASE_DIR,
      "count_of_scenarios_killing_mutant_per_mutant",
      "countOfScenariosKillingMutantPerMutant.csv",)
    Files.createDirectories(path.parent)
    path.writeText(scenarioKillingMutantPerMutant.joinToString (prefix = "Mutant, Count of scenarios killing mutant\n", separator = "\n") { "${it.first},${it.second}" })
  }

  fun calculateCountOfScenariosKillingMutant(failedMutantsMapping: List<MutantFailure>, distinctMutantIds: List<UUID>): List<Pair<UUID, Int>> {
    val scenarioKillingMutantPerMutant: List<Pair<UUID, Int>> =
      distinctMutantIds.map { id ->
        id to failedMutantsMapping.filter { it.mutantID == id }.map { it.startingScenario }.toSet().size
      }.sortedBy { it.second }

    return scenarioKillingMutantPerMutant
  }
}