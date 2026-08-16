#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/out}"

echo "[INFO] Cleaning existing mutations"
rm -rf /repo/src/main/kotlin/tools/aqua/stars/sumo/mutants
rm -rf OUT_DIR

echo "[INFO] Mutating Autopilot.kt"
echo "[INFO] Using the following mutant-kraken configuration:"

cd /repo/src/main/kotlin/tools/aqua/stars/sumo/

more mutantkraken.config.json | cat
echo

echo "[INFO] Run mutant-kraken on Autopilot.kt"
mutant-kraken mutate || true

echo "[INFO] Error expected, as gradle should not be called."

count=$(find mutant-kraken-dist/mutations -type f ! -name 'mutations.json' | grep -c .)
echo "[INFO] Successfully created $count mutants"

MUT_DIR="${1:-mutant-kraken-dist/mutations}"
TARGET_PKG="tools.aqua.stars.sumo.mutants"
MUTANT_IMPORT="tools.aqua.stars.sumo.Mutant"
ORIGINAL_SRC="Autopilot.kt"
MUTATIONS_JSON="$MUT_DIR/mutations.json"

# ---------------------------------------------------------------------------
# Load per-mutation operator info from mutations.json, so each generated mutant
# file can be matched back to the mutation operator that produced it (see the
# matching loop below). mutant-kraken's mutated filenames only encode a UUID,
# not the operator, so this has to be read from the JSON report instead.
# mutations.json is a map keyed by mutated source file path, each value holding
# a "mutations" array of {line_number, old_op, new_op, mutation_type} records
# (one per generated mutant for that file) - "id"/"start_byte"/"end_byte" are
# NOT included in the JSON, so there is no direct id -> generated-file link;
# matching is instead done via the changed line number (see below).
# ---------------------------------------------------------------------------
declare -a mutation_line_number=()
declare -a mutation_old_op=()
declare -a mutation_new_op=()
declare -a mutation_op_type=()
declare -A claimed=()

if [[ -f "$MUTATIONS_JSON" ]] && command -v jq >/dev/null 2>&1; then
  echo "[INFO] Reading mutation operator info from $MUTATIONS_JSON"
  while IFS=$'\t' read -r ln old new mtype; do
    # Defensively strip a stray trailing \r from the last field (read only strips \n) - cheap
    # insurance against a CRLF surprise anywhere upstream corrupting the last TSV column.
    mtype="${mtype%$'\r'}"
    mutation_line_number+=("$ln")
    mutation_old_op+=("$old")
    mutation_new_op+=("$new")
    mutation_op_type+=("$mtype")
  done < <(
    jq -r --arg suffix "$ORIGINAL_SRC" '
      to_entries
      | map(select(.key | endswith($suffix)))
      | if length == 0 then error("no mutations.json entry ending in \($suffix)") else .[0] end
      | .value.mutations[]
      | [.line_number, .old_op, .new_op, .mutation_type] | @tsv
    ' "$MUTATIONS_JSON"
  )
  echo "[INFO] Loaded ${#mutation_line_number[@]} mutation record(s) for operator lookup."
else
  echo "[WARN] $MUTATIONS_JSON not found or jq unavailable - mutants will not be grouped by operator." >&2
fi

echo "[INFO] Renaming + repackaging mutant files into ${TARGET_PKG}"
counter=1
mutant_names=()
mutation_types=()

while IFS= read -r file; do
  new_base="AutopilotMutant${counter}"
  new_file="$(dirname "$file")/${new_base}.kt"

  # Match this mutant back to the mutations.json record that produced it, via the line it
  # changed relative to the pristine Autopilot.kt (before the perl rewrite below touches
  # package/import/class-name lines, which would otherwise show up as unrelated diff noise).
  # "diff -U0" reports a hunk header "@@ -N ... @@" starting exactly at the changed line for a
  # single-point mutation, with no surrounding context lines to strip out.
  #
  # Both sides are compared with \r stripped (via process substitution, not touching the actual
  # files - "$file" still needs its original bytes for the perl rewrite below): if the checked-out
  # Autopilot.kt has CRLF line endings (e.g. from a Windows git checkout with core.autocrlf) but
  # mutant-kraken's output is LF-only, every single line would otherwise register as changed,
  # making "diff -U0" report line 1 for every mutant instead of its actual mutated line.
  mutation_type="Unknown"
  if [[ ${#mutation_line_number[@]} -gt 0 ]]; then
    changed_line=$(diff -U0 <(tr -d '\r' < "$ORIGINAL_SRC") <(tr -d '\r' < "$file") 2>/dev/null | grep -m1 '^@@ -' | sed -E 's/^@@ -([0-9]+).*/\1/' || true)
    if [[ -n "$changed_line" ]]; then
      original_line=$(sed -n "${changed_line}p" "$ORIGINAL_SRC" | tr -d '\r')
      mutant_line=$(sed -n "${changed_line}p" "$file" | tr -d '\r')
      matched_idx=""
      fallback_idx=""
      for idx in "${!mutation_line_number[@]}"; do
        [[ -n "${claimed[$idx]:-}" ]] && continue
        [[ "${mutation_line_number[$idx]}" == "$changed_line" ]] || continue
        if [[ -z "$fallback_idx" ]]; then fallback_idx="$idx"; fi
        old_op="${mutation_old_op[$idx]}"
        new_op="${mutation_new_op[$idx]}"
        if [[ "$original_line" == *"$old_op"* && "$mutant_line" == *"$new_op"* ]]; then
          matched_idx="$idx"
          break
        fi
      done
      # Same-line candidate with no verified old_op/new_op match (rare - e.g. an operator whose
      # token is ambiguous in context): fall back to the first unclaimed one at that line rather
      # than leaving the mutant uncategorized.
      if [[ -z "$matched_idx" ]]; then matched_idx="$fallback_idx"; fi
      if [[ -n "$matched_idx" ]]; then
        claimed[$matched_idx]=1
        mutation_type="${mutation_op_type[$matched_idx]}"
      else
        echo "[WARN] No mutations.json record at line $changed_line for $new_base - categorized as Unknown." >&2
      fi
    else
      echo "[WARN] Could not determine changed line for $new_base - categorized as Unknown." >&2
    fi
  fi
  mutation_types+=("$mutation_type")

  perl -0777 -pe "
    # 1) rewrite the first package line
    s/^([ \\t]*package)[ \\t]+[^\\n;]+/\\1 $TARGET_PKG/m;

    # 2) ensure Mutant import exists (insert after package line if missing)
    if (\$_ !~ /^\\s*import\\s+\\Q$MUTANT_IMPORT\\E\\s*$/m) {
      s/^(\\s*package[^\\n]*\\n)/\$1\\nimport $MUTANT_IMPORT\\n/m;
    }

    # 3) rename class/type token Autopilot -> AutopilotMutantX
    s/\\bAutopilot\\b/$new_base/g;
  " "$file" > "$new_file"

  rm -f "$file"
  mutant_names+=("$new_base")
  counter=$((counter + 1))
done < <(
  find "$MUT_DIR" -maxdepth 1 -type f -name '*_Autopilot.kt' -printf '%T@ %p\n' \
    | sort -n \
    | cut -d' ' -f2-
)

echo "[INFO] Collected ${#mutant_names[@]} mutants"

# Fix mutant-kraken bug: RemoveOperator glued to identifiers (e.g., RemoveOperatorwantRight)
echo "[INFO] Fixing 'RemoveOperator' bug in mutant-kraken"
find "$MUT_DIR" -type f -name '*.kt' -print0 \
  | xargs -0 sed -i -E 's/\bRemoveOperator([A-Za-z0-9_$])/\1/g'

# Group mutant indices (1-based, matching mutant_names) by the operator that produced them.
declare -A operator_indices=()
for ((i = 0; i < ${#mutant_names[@]}; i++)); do
  idx=$((i + 1))
  op="${mutation_types[$i]}"
  # Plain assignment with a ":-" default, not "+=" - appending onto a not-yet-existing
  # associative-array key via "+=" trips "unbound variable" under "set -u" in bash.
  operator_indices["$op"]="${operator_indices["$op"]:-}$idx "
done

# ArithmeticReplacementOperator -> arithmeticReplacementOperatorMutants
kotlin_property_name_for_operator() {
  local op="$1"
  local first="${op:0:1}"
  local rest="${op:1}"
  printf '%s%sMutants' "$(tr '[:upper:]' '[:lower:]' <<< "$first")" "$rest"
}

echo "[INFO] Writing mutant registry Kotlin file."
registry_file="$MUT_DIR/AutopilotMutants.kt"

echo "[INFO] Writing registry: $registry_file"

{
  echo "package $TARGET_PKG"
  echo
  echo "import kotlin.reflect.KClass"
  echo "import kotlin.reflect.full.createInstance"
  echo "import $MUTANT_IMPORT"
  echo
  echo "/** AUTO-GENERATED: registry of all Autopilot mutants. */"
  echo "object AutopilotMutants {"

  # One Set<Pair<Int, KClass<out Mutant>>> per mutation operator actually observed (see
  # mutantkraken.config.json's "general.operators" list) - unlike a hand-curated grouping, this
  # always reflects exactly which single-point mutation created each class, not a manual/combined
  # category. Each entry carries its byIndex index alongside the class (not just a bare
  # Set<KClass<out Mutant>>) so byIndex can later be assembled from a combination of these sets
  # (e.g. "(arithmeticReplacementOperatorMutants + logicalReplacementOperatorMutants).toMap()")
  # instead of hand-commenting lines out of one big mapOf(...).
  operator_prop_names=()
  for op in $(printf '%s\n' "${!operator_indices[@]}" | sort); do
    prop_name=$(kotlin_property_name_for_operator "$op")
    operator_prop_names+=("$prop_name")
    echo "  /** All mutants generated by mutant-kraken's [$op] operator, keyed by their [byIndex] index. */"
    echo "  val $prop_name ="
    echo "      setOf("
    read -ra idxs <<< "${operator_indices[$op]}"
    for idx in "${idxs[@]}"; do
      name="${mutant_names[$((idx - 1))]}"
      echo "          $idx to ${name}::class,"
    done
    echo "      )"
    echo
  done

  # Simple union of every operator group above, rather than a separately hand-maintained mapOf(...)
  # - keeps byIndex in sync with the sets above by construction instead of by convention.
  echo "  /** Mapping from index to the corresponding mutant class - the union of every operator group above. */"
  echo "  val byIndex: Map<Int, KClass<out Mutant>> ="
  if [[ ${#operator_prop_names[@]} -eq 0 ]]; then
    echo "      emptyMap()"
  else
    echo "      ("
    for i in "${!operator_prop_names[@]}"; do
      if [[ $i -eq 0 ]]; then
        echo "          ${operator_prop_names[$i]}"
      else
        echo "              + ${operator_prop_names[$i]}"
      fi
    done
    echo "      ).toMap()"
  fi
  echo
  echo "  /**"
  echo "   * Creates a new instance of the [Mutant] at the given [index]."
  echo "   *"
  echo "   * @param index The index of the [Mutant] that should be instantiated."
  echo "   * @return The instantiated Mutant."
  echo "   */"
  echo "  fun create(index: Int): Mutant {"
  echo "    if (index == -1) {"
  echo "      return tools.aqua.stars.sumo.Autopilot()"
  echo "    }"
  echo "    return byIndex[index]?.createInstance() ?: error(\"No mutant for index=\$index\")"
  echo "  }"
  echo
  echo "  /**"
  echo "   * Creates a list of new instances of [Mutant] in the range [from]..[toInclusive]."
  echo "   *"
  echo "   * @param from The start index of the range (inclusive)."
  echo "   * @param toInclusive The end index of the range (inclusive)."
  echo "   * @return A list of new instances of [Mutant] in the range [from]..[toInclusive]."
  echo "   */"
  echo "  fun createRange(from: Int, toInclusive: Int): List<Mutant> ="
  echo "    (from..toInclusive).map { create(it) }"
  echo "}"
} > "$registry_file"

echo "[Info] Copying mutants to /out"
cp -r "${MUT_DIR}"/. "${OUT_DIR}"
