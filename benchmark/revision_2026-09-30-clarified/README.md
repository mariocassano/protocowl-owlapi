# Revisione successiva al chiarimento sui fixed-point decimal

Il docente ha precisato che la parte intera negativa del formato 5 viene
codificata come `value - 1` prima di SVarInt. Il decoder ora recupera la parte
intera sommando uno e conservando separatamente il segno. Le precedenti
differenze sui nuovi binari **non erano errori del dataset**.

- `chiarimento_docente.txt`: comunicazione ricevuta e regola applicata.
- `input_manifest.json`: impronte degli input, invariati rispetto alla precedente verifica.
- `source_manifest.json`: sorgenti e configurazioni effettivamente verificati.
- `test-results/`: report XML Gradle correnti.
- `validation_and_benchmark.txt`: log della pipeline completa.
- `summary.json`: risultati di test, validazione e riepilogo statistico del benchmark.
- `result_manifest.json`: impronte dei risultati.
- `benchmark_environment.txt`: copia dell'ambiente della campagna.
- `script_tests.txt`: regressioni isolate della pubblicazione, con processi simulati.
- `verify_dataset_literals.py` e `byte_findings.json`: verifica dei sei esempi
  standard/MIS_128 con la compensazione chiarita, indipendente dal parser Java.
- `AGGIORNAMENTI_REPORT_FLC.txt`: copia delle istruzioni complete per il report.

Il CSV completo è `../../benchmark_results.csv`, le dimensioni della fase 2
sono `../../roundtrip_sizes.csv`, la copertura è `../../lib/coverage_report.csv`.
Per comandi, ambiente e metodologia vedere [BENCHMARK.md](../../BENCHMARK.md).

Il codice è una revisione locale su base main `7382144`, non ancora un nuovo
commit pubblicato. La cartella `../revision_2026-09-30` è storica e contiene
l'interpretazione precedente, ora marcata come superata. I materiali del docente
non sono stati modificati.
