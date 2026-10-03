STORICO — INTERPRETAZIONE SUPERATA DAL CHIARIMENTO DEL DOCENTE.
Le differenze sui fixed point negativi descritte sotto non sono errori del dataset:
mancava nel decoder la compensazione della parte intera negativa (value - 1).
Vedere ../revision_2026-09-30-clarified/ e il BENCHMARK.md corrente.

# Evidenze della revisione del 30 settembre 2026

Codice locale su base main `73821444f9403e54d0848367de3712cae47a6bb0`, dopo
l'adeguamento alla specifica aggiornata. Nessun nuovo commit è stato creato.

- `input_manifest.json`: SHA-256 e dimensioni dei 300 input, specifica,
  istruzioni, metadata e binari storici home/pizza.
- `source_manifest.json`: SHA-256 dei sorgenti e configurazioni verificati.
- `test-results/`: report XML Gradle copiati dall'esecuzione corrente.
- `validation.txt`: log Gradle della nuova validazione.
- `script_tests.txt`: cinque test Python della pubblicazione, con processi simulati.
- `summary.json`: conteggi e stato della verifica.
- `result_manifest.json`: impronte dei rapporti, dei CSV di validazione e della diagnostica.
- `dataset_failures.json`: dieci differenze in a, con valori e conteggi.
- `byte_findings.json`: esempi standard e MIS_128, con offset e righe Functional.
- `verify_dataset_literals.py`: verifica dei byte indipendente dal parser Java.
- `AGGIORNAMENTI_REPORT_FLC.txt`: copia del TXT nella radice del workspace,
  pronta per l'aggiornamento del report e includibile nella consegna Git.
- `SEGNALAZIONE_DATASET_AGGIORNATO.txt`: descrizione riproducibile del problema residuo.

Esito: 69/69 test rapidi, 300/300 operazioni di copertura, 190/200 round-trip,
nessuno skip e cinque test Python superati. Le dieci differenze riguardano
componenti negative dei decimali nei binari aggiornati, non i tag interi 3/4.
Il benchmark da 500 misure non è stato avviato perché la fase 2 non è superata.
Il CSV storico in `../historical/2026-09-20` non misura questa revisione.

Ripetere i controlli con i comandi in [BENCHMARK.md](../../BENCHMARK.md).
I report HTML interattivi sono rigenerabili con Gradle. Il dataset e i PDF
rimangono esterni al repository e non sono stati alterati da questa revisione.

La cartella inizialmente chiamata `dataset_onto 2` è ora `../dataset_onto`
rispetto alla radice del repository; il vecchio dataset è `../dataset_onto_old`.
I nuovi file sono stati verificati uguali alla distribuzione in
`/Users/beatriceapollo/Downloads/dataset_onto 2`.
