#!/bin/bash
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")"

OUTPUT_CSV="benchmark_results.csv"
ENVIRONMENT_REPORT="${ENVIRONMENT_REPORT:-benchmark_environment.txt}"
TIME_CMD="${TIME_CMD:-}"
BENCH_JAVA_OPTS="${BENCH_JAVA_OPTS:-}"
HEADER="Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes,MRSS_KB,CompressionRatioVsProtocOWL,SpaceSavingVsProtocOWL"

if [ -z "$TIME_CMD" ]; then
  if command -v gtime >/dev/null 2>&1; then TIME_CMD="gtime"; else TIME_CMD="/usr/bin/time"; fi
fi
if ! "$TIME_CMD" -v true >/dev/null 2>&1; then
  echo "ERROR: GNU time with -v is required. On macOS: brew install gnu-time; TIME_CMD=gtime." >&2
  exit 1
fi

# Validate phases 1 and 2 before measuring. Missing inputs or unsupported constructs stop the run.
bash ./gradlew --no-daemon :lib:test :lib:coverageTest :lib:datasetTest :lib:copyDependencies

case "$(uname)" in
  MINGW*|CYGWIN*|MSYS*) SEP=';' ;;
  *) SEP=':' ;;
esac
JAVA_CP="lib/build/classes/java/main${SEP}lib/build/classes/java/test${SEP}lib/build/resources/test${SEP}lib/build/libs/dependencies/*"
JVM_OPTS=()
if [ -n "$BENCH_JAVA_OPTS" ]; then read -r -a JVM_OPTS <<< "$BENCH_JAVA_OPTS"; fi

manifest=$(mktemp)
staged_csv=""
staged_environment=""
trap 'rm -f "$manifest" "${staged_csv:-}" "${staged_environment:-}"' EXIT
java -cp "$JAVA_CP" benchmark.DatasetFiles > "$manifest"
# Never replace the previous results when a prerequisite or measurement fails.
# Stage on the destination filesystem; publish only after complete validation.
staged_csv=$(mktemp "${OUTPUT_CSV}.XXXXXX")
staged_environment=$(mktemp "${ENVIRONMENT_REPORT}.XXXXXX")
printf '%s\n' "$HEADER" > "$staged_csv"
{
  echo "TimestampUTC: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "OperatingSystem: $(uname -a)"
  if [ "$(uname -s)" = Darwin ]; then
    echo "HardwareModel: $(sysctl -n hw.model)"
    echo "CPU: $(sysctl -n machdep.cpu.brand_string)"
    echo "CPUCount: $(sysctl -n hw.ncpu)"
    echo "MemoryBytes: $(sysctl -n hw.memsize)"
  else
    if command -v lscpu >/dev/null 2>&1; then lscpu; fi
    if [ -r /proc/meminfo ]; then head -3 /proc/meminfo; fi
  fi
  java -version 2>&1
  echo "BENCH_JAVA_OPTS: ${BENCH_JAVA_OPTS:-<empty>}"
  echo "TIME_CMD: $TIME_CMD"
  echo "DATASET_DIR: ${DATASET_DIR:-auto-detected (see manifest)}"
  echo "METADATA_FILE: ${METADATA_FILE:-dataset/metadata.csv or benchmark/metadata.csv}"
  echo "GitCommit: $(git rev-parse HEAD)"
  echo "GitStatus:"
  git status --short
  echo "Command: bash ./run_benchmark.sh"
  cat "$manifest"
} > "$staged_environment"

stat_size() { stat -c%s "$1" 2>/dev/null || stat -f%z "$1"; }
failed=0
run_task() {
  local task="$1" format="$2" ontology="$3" input="$4" standard="$5"
  local result status row mrss size standard_size ratio saving
  status=0
  # Each operation runs in its own JVM. GNU time measures the entire process MRSS.
  if [ -n "$BENCH_JAVA_OPTS" ]; then
    result=$("$TIME_CMD" -v java "${JVM_OPTS[@]}" -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$input" 2>&1) || status=$?
  else
    result=$("$TIME_CMD" -v java -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$input" 2>&1) || status=$?
  fi
  row=$(printf '%s\n' "$result" | awk -F, -v t="$task" -v f="$format" '$1==t && $2==f { print }')
  mrss=$(printf '%s\n' "$result" | awk -F: '/Maximum resident set size/ {gsub(/[[:space:]]/, "", $2); print $2}')
  if [ "$status" -ne 0 ] || [ -z "$row" ] || ! [[ "$mrss" =~ ^[0-9]+$ ]]; then
    echo "FAILED: $task,$format,$ontology" >&2
    printf '%s\n' "$result" >&2
    failed=1
    return
  fi
  size=$(stat_size "$input"); standard_size=$(stat_size "$standard")
  ratio=$(awk -v s="$size" -v p="$standard_size" 'BEGIN {printf "%.6f",s/p}')
  saving=$(awk -v s="$size" -v p="$standard_size" 'BEGIN {printf "%.6f",(s-p)/s}')
  printf '%s,%s,%s,%s\n' "$row" "$mrss" "$ratio" "$saving" >> "$staged_csv"
}

while IFS=$'\t' read -r ontology functional standard mis128; do
  run_task parse Functional "$ontology" "$functional" "$standard"
  run_task render Functional "$ontology" "$functional" "$standard"
  run_task parse ProtocOWL "$ontology" "$standard" "$standard"
  run_task render ProtocOWL "$ontology" "$standard" "$standard"
  run_task parse ProtocOWL_128 "$ontology" "$mis128" "$standard"
done < "$manifest"

# Lists all missing triples, duplicates, invalid metrics and phase-2 size mismatches.
java -cp "$JAVA_CP" benchmark.BenchmarkResults "$staged_csv" roundtrip_sizes.csv
if [ "$failed" -ne 0 ]; then exit 1; fi

mv "$staged_environment" "$ENVIRONMENT_REPORT"
mv "$staged_csv" "$OUTPUT_CSV"
echo "Published validated benchmark: $OUTPUT_CSV (environment: $ENVIRONMENT_REPORT)"
