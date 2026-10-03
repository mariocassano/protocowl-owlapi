# Verifica e benchmark

## Revisione dopo il chiarimento sui fixed-point decimal, 30 settembre 2026

Parser e renderer seguono la specifica aggiornata `ProtocOWL_Spec_v1_0.pdf`:
formato intero 3 = valore non negativo unsigned VarInt; formato 4 = modulo
unsigned VarInt di un valore negativo. Entrambi usano `BigInteger` per i valori
numerici, separatamente dai limiti degli indici strutturali. I decimali 5/6
continuano a usare SVarInt (Zig-Zag) dove previsto e sono emessi dal renderer in
forma testuale. Le forme lessicali non canoniche vengono conservate come testo.

Entrambe le revisioni hanno versione byte 1. Il parser predefinito legge la
convenzione aggiornata. I test dei riferimenti immutati `home.oprt` e `pizza.oprt`
selezionano esplicitamente `Parser.EncodingRevision.LEGACY`.
Questa opzione conserva anche la lettura fixed point storica. Non è una rilevazione automatica della revisione numerica.
La compatibilità con la vecchia tabella dei frame è un meccanismo distinto.

Il docente ha autorizzato a ignorare l'alias `pizza:` perso dal suo encoder.
Solo il confronto col riferimento storico ammette tale alias mancante, se è un
duplicato del namespace predefinito pizza. Il confronto del dataset e quello
degli output del nostro renderer restano esatti; nessun alias è inventato dal parser.

Il docente ha inoltre chiarito che nel formato fixed point 5 la parte intera
negativa viene diminuita di uno prima di SVarInt. Il parser corrente legge
SVarInt normalmente e recupera il testo come `"-" + abs(s + 1)` quando s è
negativo, conservando il segno anche per `-0.5`. La funzione Zig-Zag non cambia.
La precedente segnalazione di errori nei nuovi decimali negativi era quindi
un'interpretazione incompleta del decoder, ora corretta. I binari non sono stati modificati.

Risultati Gradle wrapper 9.0.0, JDK 24.0.1, target Java 21, heap test 4 GiB:

- **80/80 test rapidi** superati, zero skip, inclusi 43 casi in IntegerEncodingTest e 15 di robustezza.
- **300/300 operazioni di copertura**, incluse 100 scritture con rilettura e confronto.
- **200/200 round-trip** superati in a, b e c, zero skip o ontologie escluse.
- **5/5 test dello script** superati; usano processi simulati, distinti dalle misure reali.
- **500/500 misure reali completate e validate**, senza duplicati o combinazioni mancanti.

Il chiarimento e la correzione della precedente interpretazione sono descritti in
[known_dataset_issues.md](benchmark/known_dataset_issues.md). Le evidenze correnti
sono in [revision_2026-09-30-clarified](benchmark/revision_2026-09-30-clarified/README.md).

## Dataset corrente ed esecuzione

La nuova distribuzione, inizialmente `dataset_onto 2`, è ora nella cartella
`../dataset_onto`. La vecchia è in `../dataset_onto_old`. I 100 Functional sono
invariati, mentre tutti i 200 binari sono cambiati. Le impronte SHA-256 sono in
[revision_2026-09-30-clarified/input_manifest.json](benchmark/revision_2026-09-30-clarified/input_manifest.json).

Dalla radice `protocowl-owlapi`:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-24.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export DATASET_DIR="$(cd ../dataset_onto && pwd)"
export METADATA_FILE="$PWD/benchmark/metadata.csv"
bash ./gradlew --no-daemon :lib:test
bash ./gradlew --no-daemon :lib:coverageTest
bash ./gradlew --no-daemon :lib:datasetTest
python3 benchmark/test_run_benchmark.py
```

Adattare il percorso JDK ad altre macchine. Senza `DATASET_DIR`, vengono cercate
nell'ordine `../dataset_onto 2`, `../dataset_onto`, `../dataset`. Impostare comunque
il percorso esplicito per non confondere revisioni differenti del dataset.
Sono accettati `protocowl/std` o `protocowl/standard`, `.oprt` e `*_protocowl.owl`.

La nuova distribuzione non contiene `metadata.csv`. L'elenco incluso nel progetto
coincide con tutte le 100 ontologie ricevute. `METADATA_FILE` (o `-PmetadataFile`)
può indicarne un altro; altrimenti si cerca nel dataset e poi in `benchmark/`.
`-PdatasetDir` è l'alternativa Gradle a `DATASET_DIR`. Input mancanti, duplicati o
un numero di ontologie diverso da 100 causano errore, senza filtrare il campione.

`coverageTest` scrive `lib/coverage_report.csv`; `datasetTest` produce
`roundtrip_sizes.csv`, con tutte le 200 righe e dimensioni uguali fra le due varianti di ogni ontologia.
I report HTML sono in `lib/build/reports/tests/{test,coverageTest,datasetTest}`.
Le evidenze della revisione sono conservate anche in
[benchmark/revision_2026-09-30-clarified](benchmark/revision_2026-09-30-clarified/README.md), insieme
alle impronte dei sorgenti. La revisione è una modifica locale su base `7382144`,
non un nuovo commit pubblicato.

## Campagna completata ed esecuzione riproducibile

Servono Bash e GNU time con `-v`. Il BSD `/usr/bin/time` di macOS non basta.
In questa macchina GNU time 1.9 è stato compilato nel workspace:

```bash
export TIME_CMD="$(cd ../../tmp/revisione_2026-09-30/tools/gnu-time/bin && pwd)/time"
"$TIME_CMD" -v true
# Opzioni effettivamente usate nella campagna corrente.
export BENCH_JAVA_OPTS="-Xms256m -Xmx4g"
bash ./run_benchmark.sh
```

Su altre macchine usare il proprio GNU time, ad esempio `TIME_CMD=gtime`.
Il sorgente proviene da `https://ftp.gnu.org/gnu/time/time-1.9.tar.gz`; SHA-256:
`fbacf0c81e62429df3e33bda4cee38756604f18e01d977338e23306a3e3b521e`.
La configurazione macOS converte `ru_maxrss` da byte a KB.

Lo script esegue prima tutti i prerequisiti, quindi una JVM per ciascuna delle
500 combinazioni. Il tempo è misurato internamente da `BenchmarkTask`; il render
esclude la preparazione. MRSS riguarda l'intero processo Java, inclusi JVM,
OWLAPI e preparazione. MIS_128 è ammesso solo in parsing.

CSV e ambiente vengono pubblicati solo dopo validazione completa. Un errore
non sostituisce gli ultimi risultati riusciti. Il nuovo
`benchmark_results.csv` ha 500 righe validate; l'ambiente è registrato in
`benchmark_environment.txt`. Il benchmark precedente resta immutato in
[benchmark/historical/2026-09-20](benchmark/historical/2026-09-20/README.md):
non misura il codice né il dataset aggiornati.

## Risultati della campagna corrente

Mac15,6, Apple M3 Pro, 12 CPU logiche, 18 GiB RAM; Darwin 25.6.0 arm64;
JDK 24.0.1; GNU time 1.9; JVM di misura `-Xms256m -Xmx4g`.
Una misura per combinazione, ogni volta in una nuova JVM, senza warm-up dedicato.
Le mediane sono calcolate sulle 100 ontologie, non su ripetizioni della stessa.

| Task | Formato | N | Tempo mediano (ms) | MRSS mediano (KB) |
| --- | --- | ---: | ---: | ---: |
| parse | Functional | 100 | 155.619 | 135168 |
| parse | ProtocOWL | 100 | 93.921 | 117224 |
| parse | ProtocOWL_128 | 100 | 106.307 | 129896 |
| render | Functional | 100 | 69.026 | 146664 |
| render | ProtocOWL | 100 | 106.615 | 153936 |

I valori completi sono nel CSV; totali dimensionali e riepiloghi in
[summary.json](benchmark/revision_2026-09-30-clarified/summary.json).
MRSS riguarda l'intero processo Java, inclusa la preparazione esclusa dal tempo
nel render. I risultati descrivono questa campagna e non una superiorità generale.
