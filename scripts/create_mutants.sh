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

echo "[INFO] Renaming + repackaging mutant files into ${TARGET_PKG}"
counter=1
mutant_names=()

while IFS= read -r file; do
  new_base="AutopilotMutant${counter}"
  new_file="$(dirname "$file")/${new_base}.kt"

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
  echo "  /** Mapping from index to the corresponding mutant class. */"
  echo "  val byIndex: Map<Int, KClass<out Mutant>> = mapOf("

  for ((i=0; i<${#mutant_names[@]}; i++)); do
    idx=$((i + 1))
    name="${mutant_names[$i]}"
    if [[ $i -lt $((${#mutant_names[@]} - 1)) ]]; then
      echo "    $idx to ${name}::class,"
    else
      echo "    $idx to ${name}::class"
    fi
  done

  echo "  )"
  echo
  echo "  /**"
  echo "   * Creates a new instance of the [Mutant] at the given [index]."
  echo "   *"
  echo "   * @param index The index of the [Mutant] that should be instantiated."
  echo "   * @return The instantiated Mutant."
  echo "   */"
  echo "  fun create(index: Int): Mutant {"
  echo "    if (index == -1) {"
  echo "      return Autopilot()"
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