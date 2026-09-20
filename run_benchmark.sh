#!/bin/bash

set -euo pipefail

DATASET_DIR="${DATASET_DIR:-../../FLC step 2/dataset_onto}"
METADATA_FILE="${METADATA_FILE:-$DATASET_DIR/metadata.csv}"
OUTPUT_CSV="benchmark_results.csv"
ENVIRONMENT_REPORT="${ENVIRONMENT_REPORT:-benchmark_environment.txt}"
# Auto-rilevamento di GNU time: su macOS cerca 'gtime' di Homebrew, altrimenti '/usr/bin/time'
if [ -z "${TIME_CMD:-}" ]; then
  if command -v gtime >/dev/null 2>&1; then
    TIME_CMD="gtime"
  elif [ -x "/opt/homebrew/bin/gtime" ]; then
    TIME_CMD="/opt/homebrew/bin/gtime"
  else
    TIME_CMD="/usr/bin/time"
  fi
fi
BENCH_JAVA_OPTS="${BENCH_JAVA_OPTS:-}"
CSV_HEADER="Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes,MRSS_KB,CompressionRatioVsProtocOWL,SpaceSavingVsProtocOWL"

# --- RILEVAMENTO CLASSPATH ---
# Su Windows (Git Bash in IntelliJ) Java richiede il punto e virgola ';'
# Su Linux/Mac/WSL richiede i due punti ':'
if uname | grep -q "MINGW\|CYGWIN\|MSYS"; then
  JAVA_CP="lib/build/classes/java/main;lib/build/classes/java/test;lib/build/resources/test;lib/build/libs/dependencies/*"
else
  JAVA_CP="lib/build/classes/java/main:lib/build/classes/java/test:lib/build/resources/test:lib/build/libs/dependencies/*"
fi

echo "Compilazione del progetto con Gradle in corso..."
bash ./gradlew --no-daemon :lib:classes :lib:testClasses :lib:copyDependencies

write_environment_report() {
  {
    echo "Benchmark environment"
    echo "TimestampUTC: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "Host: $(hostname)"
    echo "OperatingSystem: $(uname -a)"
    if command -v sysctl >/dev/null 2>&1; then
      echo "HardwareModel: $(sysctl -n hw.model 2>/dev/null || true)"
      echo "CPUCount: $(sysctl -n hw.ncpu 2>/dev/null || true)"
      echo "MemoryBytes: $(sysctl -n hw.memsize 2>/dev/null || true)"
    elif command -v lscpu >/dev/null 2>&1; then
      echo "Hardware:"
      lscpu
    else
      echo "Hardware: unavailable"
    fi
    echo "JavaVersion:"
    java -version 2>&1
    echo "BENCH_JAVA_OPTS: ${BENCH_JAVA_OPTS:-<empty>}"
    echo "TIME_CMD: $TIME_CMD"
    echo "Dataset: $DATASET_DIR"
    echo "Metadata: $METADATA_FILE"
    echo "Command: bash ./run_benchmark.sh"
  } > "$ENVIRONMENT_REPORT"
}

write_environment_report

# Il file dei risultati viene sempre ricreato, anche se un prerequisito fallisce.
printf '%s\n' "$CSV_HEADER" > "$OUTPUT_CSV"

if [ ! -f "$METADATA_FILE" ]; then
  echo "ERROR: metadata.csv non trovato: $METADATA_FILE" >&2
  echo "Impostare METADATA_FILE oppure aggiungere metadata.csv alla radice del dataset." >&2
  exit 1
fi

if ! "$TIME_CMD" -v true >/dev/null 2>&1; then
  echo "ERROR: serve GNU time con supporto a '-v' per misurare MRSS_KB." >&2
  echo "Su macOS installare GNU time (brew install gnu-time) e impostare TIME_CMD, ad esempio TIME_CMD=gtime." >&2
  exit 1
fi

SEEN_RESULTS=""
declare -a FAILED_COMBINATIONS=()

read -r -a JVM_OPTS <<< "$BENCH_JAVA_OPTS"

normalize_base_name() {
  local name="$1"
  name="${name##*/}"
  name="${name%_functional.owl}"
  name="${name%_protocowl.owl}"
  printf '%s' "$name"
}

metadata_bases() {
  local filename_column
  filename_column=$(awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "filename") { print i; exit } }' "$METADATA_FILE")
  if [ -z "$filename_column" ]; then
    echo "ERROR: metadata.csv non contiene la colonna filename" >&2
    return 1
  fi

  awk -F, -v column="$filename_column" 'NR > 1 && $column != "" { gsub(/^"|"$|\r/, "", $column); print $column }' "$METADATA_FILE" |
    while IFS= read -r filename; do
      ontology=$(normalize_base_name "$filename")
      [ -f "$DATASET_DIR/functional/${ontology}_functional.owl" ] || continue
      [ -f "$DATASET_DIR/protocowl/std/${ontology}_protocowl.owl" ] || continue
      [ -f "$DATASET_DIR/protocowl/MIS_128/${ontology}_protocowl.owl" ] || continue
      printf '%s\n' "$ontology"
    done | sort -u
}

run_task() {
  local task="$1" format="$2" file="$3" ontology="$4" format_size="$5" protoc_size="$6"
  local compression_ratio space_saving cmd_output exit_code csv_line mrss result_key

  compression_ratio=$(awk -v fs="$format_size" -v ps="$protoc_size" 'BEGIN { if (ps==0) print "NA"; else printf "%.6f", fs/ps }')
  space_saving=$(awk -v fs="$format_size" -v ps="$protoc_size" 'BEGIN { if (fs==0) print "NA"; else printf "%.6f", (fs-ps)/fs }')

  set +e
  if [ -n "$BENCH_JAVA_OPTS" ]; then
    cmd_output=$("$TIME_CMD" -v java "${JVM_OPTS[@]}" -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$file" 2>&1)
  else
    cmd_output=$("$TIME_CMD" -v java -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$file" 2>&1)
  fi
  exit_code=$?
  set -e

  csv_line=$(printf '%s\n' "$cmd_output" | grep "^$task,$format," | tail -1 || true)
  mrss=$(printf '%s\n' "$cmd_output" | awk -F: '/Maximum resident set size/ {gsub(/ /, "", $2); print $2}' | tail -1)

  if [ "$exit_code" -eq 0 ] && [ -n "$csv_line" ] && [ -n "$mrss" ]; then
    result_key=$(printf '%s' "$csv_line" | cut -d',' -f1-3)
    if [[ "$SEEN_RESULTS" == *"|$result_key|"* ]]; then
      echo "ERROR: risultato duplicato per $result_key" >&2
      return 1
    fi
    SEEN_RESULTS="${SEEN_RESULTS}|$result_key|"
    echo "$csv_line,$mrss,$compression_ratio,$space_saving" >> "$OUTPUT_CSV"
  else
    echo "ERROR: benchmark fallito per $task $format $ontology" >&2
    printf '%s\n' "$cmd_output" >&2
    FAILED_COMBINATIONS+=("$task,$format,$ontology")
    return 0
  fi
}

validate_results() {
  local validation_errors=0
  local actual_rows

  actual_rows=$(($(wc -l < "$OUTPUT_CSV") - 1))
  if [ "$actual_rows" -ne 500 ]; then
    echo "ERROR: attese 500 righe dati, trovate $actual_rows" >&2
    validation_errors=1
  fi

  while IFS= read -r missing; do
    [ -n "$missing" ] || continue
    echo "MISSING: $missing" >&2
    validation_errors=1
  done < <(
    for ontology in "${ONTOLOGIES[@]}"; do
      printf '%s\n' \
        "parse,Functional,$ontology" \
        "render,Functional,$ontology" \
        "parse,ProtocOWL,$ontology" \
        "render,ProtocOWL,$ontology" \
        "parse,ProtocOWL_128,$ontology"
    done | awk -F, 'NR == FNR { expected[$0] = 1; next } NR > 1 { actual[$1 "," $2 "," $3] = 1 } END { for (key in expected) if (!(key in actual)) print key }' - <(tail -n +2 "$OUTPUT_CSV") | sort
  )

  while IFS=, read -r task format ontology time_ms input_size output_size mrss ratio saving; do
    [ -n "$ontology" ] || continue
    local expected_input expected_protoc
    case "$format" in
      Functional) expected_input=$(stat_size "$DATASET_DIR/functional/${ontology}_functional.owl") ;;
      ProtocOWL) expected_input=$(stat_size "$DATASET_DIR/protocowl/std/${ontology}_protocowl.owl") ;;
      ProtocOWL_128) expected_input=$(stat_size "$DATASET_DIR/protocowl/MIS_128/${ontology}_protocowl.owl") ;;
      *) echo "ERROR: formato non valido nel CSV: $format" >&2; validation_errors=1; continue ;;
    esac
    expected_protoc=$(stat_size "$DATASET_DIR/protocowl/std/${ontology}_protocowl.owl")

    if [ "$task" != "parse" ] && [ "$task" != "render" ]; then
      echo "ERROR: task non valido nel CSV: $task,$format,$ontology" >&2
      validation_errors=1
    fi
    if [ "$format" = "ProtocOWL_128" ] && [ "$task" = "render" ]; then
      echo "ERROR: render presente per ProtocOWL_128: $task,$format,$ontology" >&2
      validation_errors=1
    fi
    if [ "$input_size" -ne "$expected_input" ]; then
      echo "ERROR: InputSizeBytes errato per $task,$format,$ontology: $input_size != $expected_input" >&2
      validation_errors=1
    fi
    if [ "$task" = "parse" ] && [ "$output_size" -ne 0 ]; then
      echo "ERROR: OutputSizeBytes del parse diverso da 0: $task,$format,$ontology" >&2
      validation_errors=1
    fi
    if [ "$task" = "render" ] && [ "$output_size" -le 0 ]; then
      echo "ERROR: OutputSizeBytes del render non valido (<= 0): $task,$format,$ontology" >&2
      validation_errors=1
    fi
    if ! [[ "$time_ms" =~ ^[0-9]+([.][0-9]+)?$ && "$mrss" =~ ^[0-9]+$ ]]; then
      echo "ERROR: metriche non numeriche per $task,$format,$ontology" >&2
      validation_errors=1
    fi
  done < <(tail -n +2 "$OUTPUT_CSV")

  duplicate_keys=$(tail -n +2 "$OUTPUT_CSV" | cut -d, -f1-3 | sort | uniq -d)
  if [ -n "$duplicate_keys" ]; then
    echo "ERROR: combinazioni duplicate:" >&2
    printf '%s\n' "$duplicate_keys" >&2
    validation_errors=1
  fi

  return "$validation_errors"
}

stat_size() {
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

echo "Inizio il benchmark su dataset esistente. I risultati verranno salvati in $OUTPUT_CSV"
echo "Ambiente annotato in $ENVIRONMENT_REPORT"

ONTOLOGIES=()
while IFS= read -r onto; do
  [ -n "$onto" ] && ONTOLOGIES+=("$onto")
done < <(metadata_bases)

ontology_count="${#ONTOLOGIES[@]}"
if [ "$ontology_count" -ne 100 ]; then
  echo "ERROR: metadata.csv deve contenere esattamente 100 ontologie complete, trovate $ontology_count" >&2
  exit 1
fi

for ontology in "${ONTOLOGIES[@]}"; do
  functional_file="$DATASET_DIR/functional/${ontology}_functional.owl"
  protocowl_file="$DATASET_DIR/protocowl/std/${ontology}_protocowl.owl"
  protocowl_128_file="$DATASET_DIR/protocowl/MIS_128/${ontology}_protocowl.owl"

  for required_file in "$functional_file" "$protocowl_file" "$protocowl_128_file"; do
    if [ ! -f "$required_file" ]; then
      echo "ERROR: file mancante per $ontology: $required_file" >&2
      exit 1
    fi
  done

  protoc_size=$(stat_size "$protocowl_file")
  functional_size=$(stat_size "$functional_file")
  protocowl_128_size=$(stat_size "$protocowl_128_file")

  run_task parse Functional "$functional_file" "$ontology" "$functional_size" "$protoc_size"
  run_task render Functional "$functional_file" "$ontology" "$functional_size" "$protoc_size"
  run_task parse ProtocOWL "$protocowl_file" "$ontology" "$protoc_size" "$protoc_size"
  run_task render ProtocOWL "$protocowl_file" "$ontology" "$protoc_size" "$protoc_size"
  run_task parse ProtocOWL_128 "$protocowl_128_file" "$ontology" "$protocowl_128_size" "$protoc_size"
done

if [ "${#FAILED_COMBINATIONS[@]}" -gt 0 ]; then
  echo "ERROR: combinazioni fallite durante l'esecuzione:" >&2
  printf '  %s\n' "${FAILED_COMBINATIONS[@]}" >&2
fi

if ! validate_results; then
  echo "ERROR: controlli 6.4 falliti." >&2
  exit 1
fi

echo "Benchmark completato con successo: 500 righe dati, nessun duplicato e metriche coerenti."
