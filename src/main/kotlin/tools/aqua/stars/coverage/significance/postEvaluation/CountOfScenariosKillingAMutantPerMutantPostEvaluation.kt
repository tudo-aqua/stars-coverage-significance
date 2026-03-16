package tools.aqua.stars.coverage.significance.postEvaluation

import tools.aqua.stars.coverage.significance.postEvaluation.dataclasses.MutantFailure
import java.util.UUID

object CountOfScenariosKillingAMutantPerMutantPostEvaluation {

  fun evaluate(failedMutantsMapping: List<MutantFailure>, distinctMutantIds: List<UUID>) {
    val scenarioKillingMutantPerMutant : MutableMap<UUID, Int> = mutableMapOf()

    distinctMutantIds.forEach { id ->
      val failuresForMutant = failedMutantsMapping.filter { it.mutantID == id }
    }
  }
}