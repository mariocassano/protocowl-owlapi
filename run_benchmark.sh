#!/bin/bash

set -euo pipefail

DATASET_DIR="${DATASET_DIR:-../../FLC step 2/dataset_onto}"
METADATA_FILE="${METADATA_FILE:-$DATASET_DIR/metadata.csv}"
OUTPUT_CSV="benchmark_results.csv"
ENVIRONMENT_REPORT="${ENVIRONMENT_REPORT:-benchmark_environment.txt}"
TIME_CMD="${TIME_CMD:-/usr/bin/time}"
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
  echo "Su macOS installare GNU time e impostare TIME_CMD, ad esempio TIME_CMD=gtime." >&2
  exit 1
fi

declare -A SEEN_RESULTS=()

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
  cmd_output=$("$TIME_CMD" -v java "${JVM_OPTS[@]}" -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$file" 2>&1)
  exit_code=$?
  set -e

  csv_line=$(printf '%s\n' "$cmd_output" | grep "^$task,$format," | tail -1 || true)
  mrss=$(printf '%s\n' "$cmd_output" | awk -F: '/Maximum resident set size/ {gsub(/ /, "", $2); print $2}' | tail -1)

  if [ "$exit_code" -eq 0 ] && [ -n "$csv_line" ] && [ -n "$mrss" ]; then
    result_key=$(printf '%s' "$csv_line" | cut -d',' -f1-3)
    if [[ -n "${SEEN_RESULTS[$result_key]:-}" ]]; then
      echo "ERROR: risultato duplicato per $result_key" >&2
      return 1
    fi
    SEEN_RESULTS[$result_key]=1
    echo "$csv_line,$mrss,$compression_ratio,$space_saving" >> "$OUTPUT_CSV"
  else
    echo "ERROR: benchmark fallito per $task $format $ontology" >&2
    printf '%s\n' "$cmd_output" >&2
    return 1
  fi
}

echo "Inizio il benchmark su dataset esistente. I risultati verranno salvati in $OUTPUT_CSV"
echo "Ambiente annotato in $ENVIRONMENT_REPORT"

while IFS= read -r ontology; do
  [ -n "$ontology" ] || continue
  functional_file="$DATASET_DIR/functional/${ontology}_functional.owl"
  protocowl_file="$DATASET_DIR/protocowl/std/${ontology}_protocowl.owl"
  protocowl_128_file="$DATASET_DIR/protocowl/MIS_128/${ontology}_protocowl.owl"

  for required_file in "$functional_file" "$protocowl_file" "$protocowl_128_file"; do
    if [ ! -f "$required_file" ]; then
      echo "ERROR: file mancante per $ontology: $required_file" >&2
      exit 1
    fi
  done

  protoc_size=$(stat -c%s "$protocowl_file" 2>/dev/null || stat -f%z "$protocowl_file")
  functional_size=$(stat -c%s "$functional_file" 2>/dev/null || stat -f%z "$functional_file")
  protocowl_128_size=$(stat -c%s "$protocowl_128_file" 2>/dev/null || stat -f%z "$protocowl_128_file")

  run_task parse Functional "$functional_file" "$ontology" "$functional_size" "$protoc_size"
  run_task render Functional "$functional_file" "$ontology" "$functional_size" "$protoc_size"
  run_task parse ProtocOWL "$protocowl_file" "$ontology" "$protoc_size" "$protoc_size"
  run_task render ProtocOWL "$protocowl_file" "$ontology" "$protoc_size" "$protoc_size"
  run_task parse ProtocOWL_128 "$protocowl_128_file" "$ontology" "$protocowl_128_size" "$protoc_size"
done

ontology_count=$(metadata_bases | awk 'NF {count++} END {print count + 0}')
if [ "$ontology_count" -ne 100 ]; then
  echo "ERROR: metadata.csv deve contenere esattamente 100 ontologie, trovate $ontology_count" >&2
  exit 1
fi

expected_rows=500
actual_rows=$(($(wc -l < "$OUTPUT_CSV") - 1))
if [ "$actual_rows" -ne "$expected_rows" ]; then
  echo "ERROR: attese $expected_rows righe dati, trovate $actual_rows" >&2
  exit 1
fi

echo "Benchmark completato con successo: $actual_rows righe dati."
