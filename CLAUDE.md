# Notes for coding agents

The current research plan and its decisions are in `docs/test-bed-plan.md`. Read it before working on
kill matrices, decision trees, scenario drawing or post-evaluation.

The next iteration (speed-grid scenarios, Autopilot v2, the MutantKraken fork and mutant screening) and
its order of work are in `docs/v2-scenarios-and-mutants-plan.md`. Read it before working on scenario
generation, the Autopilot or mutant generation.

- Items marked "Decided" there are fixed. Items marked "Proposed" or "Still open" are not: implement
them as configurable parameters with the listed default, and do not settle them in code comments
or commit messages.
- Keep the old tick-level pipeline (`scripts/decision_tree_g0.py`,
`DrawTicksWithDecisionTreeGroupingPostEvaluation.kt`) unchanged; build the new one alongside it.
- `NOTES.md` documents simulation determinism and the identical tick-0 state across controllers.
- Every coding task should be done in Kotlin. Before finishing the task, build the project and execute "spotlessApply"