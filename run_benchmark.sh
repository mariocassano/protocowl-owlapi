#!/bin/bash

set -euo pipefail

DATASET_DIR="../dataset_filtrato"
OUTPUT_CSV="benchmark_results.csv"

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

echo "Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes,MRSS_KB,CompressionRatioVsProtocOWL,SpaceSavingVsProtocOWL" > "$OUTPUT_CSV"

declare -A FORMAT_EXTS=(
  ["Functional"]=".func"
  ["Manchester"]=".omn"
  ["OWLXML"]=".owx"
  ["RDFXML"]=".rdf"
  ["Turtle"]=".ttl"
  ["ProtocOWL"]=".oprt"
)

declare -A SEEN_RESULTS=()

TASKS=("parse" "render")
BENCH_JAVA_OPTS="${BENCH_JAVA_OPTS:-}"

echo "Inizio il benchmark su dataset esistente. I risultati verranno salvati in $OUTPUT_CSV"

for base_file in "$DATASET_DIR"/onto_*.rdf; do
  [ -e "$base_file" ] || continue
  base_no_ext="${base_file%.rdf}"
  protoc_file="$base_no_ext${FORMAT_EXTS[ProtocOWL]}"
  [ -f "$protoc_file" ] || continue

  # Usa stat compatibile con vari ambienti
  protoc_size=$(stat -c%s "$protoc_file" 2>/dev/null || stat -f%z "$protoc_file")

  for format in Functional Manchester OWLXML RDFXML Turtle ProtocOWL; do
    ext="${FORMAT_EXTS[$format]}"
    file="$base_no_ext$ext"
    [ -f "$file" ] || continue

    format_size=$(stat -c%s "$file" 2>/dev/null || stat -f%z "$file")

    compression_ratio=$(awk -v fs="$format_size" -v ps="$protoc_size" 'BEGIN { if (ps==0) print "NA"; else printf "%.6f", fs/ps }')
    space_saving=$(awk -v fs="$format_size" -v ps="$protoc_size" 'BEGIN { if (fs==0) print "NA"; else printf "%.6f", (fs-ps)/fs }')

    for task in "${TASKS[@]}"; do
      # Avvia l'eseguibile Java passando per GNU Time
      CMD_OUTPUT=$(/usr/bin/time -v java $BENCH_JAVA_OPTS -cp "$JAVA_CP" benchmark.BenchmarkTask "$task" "$format" "$file" 2>&1 || true)

      CSV_LINE=$(echo "$CMD_OUTPUT" | grep "^$task,$format," || true)
      MRSS=$(echo "$CMD_OUTPUT" | grep "Maximum resident set size" | awk '{print $6}' || true)

      if [ -n "$CSV_LINE" ] && [ -n "$MRSS" ]; then
        RESULT_KEY=$(echo "$CSV_LINE" | cut -d',' -f1-3)

        if [[ -n "${SEEN_RESULTS[$RESULT_KEY]:-}" ]]; then
          echo "WARN: risultato duplicato ignorato per $RESULT_KEY" >&2
          continue
        fi

        SEEN_RESULTS[$RESULT_KEY]=1
        echo "$CSV_LINE,$MRSS,$compression_ratio,$space_saving" >> "$OUTPUT_CSV"
      else
        echo "WARN: benchmark fallito per $task $format su $(basename "$file")" >&2
      fi
    done
  done
done

echo "Benchmark completato con successo!"
