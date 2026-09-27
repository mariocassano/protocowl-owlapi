# Verifica e benchmark

## Stato dopo le correzioni del 26 settembre 2026

Le correzioni locali su base `02eb4b7` rendono rigoroso il confronto dei prefissi,
rimuovono gli alias inventati dal parser e impediscono overflow dei VarInt
strutturali. Le cardinalità OWLAPI fino a `Integer.MAX_VALUE` sono conservate
usando un campo compattato a 32 bit. Le stringhe vengono lette progressivamente,
senza allocare in anticipo tutta la lunghezza dichiarata da un input troncato.

La specifica v1 vieta di dichiarare i namespace riservati. Il renderer rifiuta
quindi, prima di emettere byte, un alias aggiuntivo di tali namespace o una mappa
che ometta un prefisso riservato implicito. Gli alias dei namespace non riservati,
i prefissi inutilizzati e il default esplicitamente dichiarato vengono conservati.
Non si aggiungono prefissi per rendere uguali due mappe diverse.

Verifica dei sorgenti ricompilati con JDK 24 (`javac --release 21`, TestNG):

- 36 casi rapidi: 35 superati, uno fallito nel confronto con `pizza.oprt`.
  Il Functional dichiara `pizza:`, il binario fornito no; il precedente parser
  inventava l'alias. Il round-trip del renderer corrente su pizza passa.
- Tutte le 14 nuove regressioni in `CodecRobustnessTest` passano.
- Copertura: 300/300 operazioni riuscite, incluse 100 scritture con confronto.
- Dataset: 200 casi, 190 superati e 10 falliti in a per i decimali dei binari
  forniti. Nessun caso saltato, nessuna ontologia esclusa.
- Cinque test della pubblicazione dei risultati passano. Usano JVM/time simulati
  e verificano il comportamento dello script, non prestazioni reali.

I problemi dei riferimenti sono descritti in
[known_dataset_issues.md](benchmark/known_dataset_issues.md). La campagna completa
resta bloccata finché questi controlli non passano. Non modificare il Functional,
non ignorare i prefissi e non filtrare le ontologie per ottenere risultati verdi.

## Ambiente e dataset

Usare JDK 21 o successivo, compatibile con il wrapper Gradle incluso. La verifica
locale è stata eseguita con JDK 24. Esempio per questa macchina:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-24.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Il dataset deve essere in `../dataset` o `../dataset_onto`, oppure impostare
`DATASET_DIR` (per Gradle anche `-PdatasetDir=...`). Sono riconosciute le cartelle
`protocowl/standard` e `protocowl/std`, i file `.oprt` e i nomi `*_protocowl.owl`.

Il manifest è `DATASET_DIR/metadata.csv`, se presente, oppure
`benchmark/metadata.csv`; `METADATA_FILE` o `-PmetadataFile=...` lo sostituisce.
Sono richieste tutte le 100 ontologie e le tre rappresentazioni. Il manifest
incluso non seleziona le ontologie in base al risultato dei test.

## Controlli nell'ordine richiesto

Dalla radice del progetto:

```bash
bash ./gradlew --no-daemon :lib:test
bash ./gradlew --no-daemon :lib:coverageTest
bash ./gradlew --no-daemon :lib:datasetTest
python3 benchmark/test_run_benchmark.py
```

`coverageTest` scrive `lib/coverage_report.csv`. `datasetTest` riporta ogni coppia
ontologia/variante e fase a/b/c e produce `roundtrip_sizes.csv`: 200 righe attese,
190 attuali perché i dieci casi falliti in a non raggiungono c. I report HTML
Gradle sono in `lib/build/reports/tests/<task>/index.html`.

La verifica del 26 settembre è stata eseguita direttamente con javac e TestNG,
con heap fino a 4 GiB, perché l'ambiente di revisione non permette al wrapper
di creare il lock nella cache Gradle esterna al workspace. Le evidenze sono in
`../../tmp/correzioni_report_2026-09-26/`, separatamente dai report Gradle storici.

## Misurazione e archiviazione

Servono Bash e GNU time con `-v`. Su macOS il BSD `/usr/bin/time` non basta:
usare `gtime` e, se necessario, `TIME_CMD=gtime`.

```bash
export BENCH_JAVA_OPTS="-Xms2g -Xmx8g"  # adattare alla macchina; esempio, non una misura
bash ./run_benchmark.sh
```

Lo script esegue prima i prerequisiti e poi una JVM per ciascuna delle 500
combinazioni. Il tempo è misurato all'interno di `BenchmarkTask`; MRSS riguarda
l'intero processo Java. Il render MIS_128 è vietato esplicitamente.

CSV e ambiente vengono scritti in file temporanei e sostituiscono
`benchmark_results.csv` e `benchmark_environment.txt` solo dopo la validazione
completa. Se GNU time manca, un test fallisce, una misura non riesce o il CSV
non supera i controlli, i risultati precedenti non vengono sovrascritti.

I vecchi risultati sono stati spostati **senza modificarne i byte** in
[benchmark/historical/2026-09-20](benchmark/historical/2026-09-20/README.md).
Misurano il codice precedente. Esempio: il render di `00022.owl` occupava 3999
byte nel CSV storico e oggi ne occupa 4000. Le dimensioni non possono essere
aggiornate a mano mantenendo i vecchi tempi e MRSS: occorre una nuova campagna.
Attualmente non esiste un CSV finale della versione corretta.
