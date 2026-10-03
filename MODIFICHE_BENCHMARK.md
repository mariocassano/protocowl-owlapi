# Modifiche alla fase benchmark e lettura della storia Git

Nota: questo documento descrive i commit precedenti alle revisioni del
26 e 30 settembre. Per lo stato attuale, inclusi la nuova codifica degli interi,
i chiarimenti sul prefisso pizza e sulla parte intera dei decimali negativi,
fare riferimento a [BENCHMARK.md](BENCHMARK.md).

Questa guida descrive le correzioni rispetto al commit `06d4163` del branch
`review/pr-3`. Le correzioni sono state sviluppate e verificate il 24 settembre
2026 e successivamente organizzate in commit tematici su `fix/benchmark-compliance`.
I commit rappresentano gruppi di modifiche coerenti, non ogni salvataggio o
esperimento intermedio. Non è stata riscritta la storia precedente.

## 1. Codec: lettura e scrittura senza omissioni silenziose

Commit: `b0c0e6f` — `fix(codec): preserva assiomi, annotazioni e valori ProtocOWL`.

File: `Parser.java`, `Renderer.java`, `Constants.java`, nella cartella
`lib/src/main/java/it/poliba/sisinflab/protocowl/`.

### Assiomi e annotazioni

Prima il renderer visitava separatamente dichiarazioni, un elenco incompleto di
assiomi logici e annotation assertion. Per un assioma logico non riconosciuto
poteva semplicemente non scrivere nulla, senza eccezione. Ad esempio una ontologia
contenente solo `DisjointUnion(A B C)` veniva riletta senza quell'assioma.

Ora ogni assioma passa per un dispatch esplicito. La scrittura è stata estesa a:

- DisjointUnion e SubPropertyChainOf;
- SubDataPropertyOf, EquivalentDataProperties, DisjointDataProperties;
- DataPropertyDomain, DataPropertyRange e FunctionalDataProperty;
- DatatypeDefinition e HasKey;
- NegativeObjectPropertyAssertion e NegativeDataPropertyAssertion;
- SubAnnotationPropertyOf, AnnotationPropertyDomain e AnnotationPropertyRange.

Se il tipo non è supportato, il renderer lancia un'eccezione esplicita. Le regole
SWRL, per esempio, vengono rifiutate, non ignorate. Non significa che sia stato
aggiunto il supporto a ogni possibile costrutto OWL.

Prima le annotazioni degli assiomi si perdevano. Ora `writeAxiomHeader` scrive il
bit annotazioni e il relativo array; il parser legge l'array e applica le
annotazioni all'assioma tramite `addAxiom`. Questo vale anche per declaration e
annotation assertion. Sono state corrette anche le annotazioni annidate: nella
forma annotata il protocollo memorizza l'identificativo della proprietà senza
l'offset usato nella forma semplice.

### Catene di proprietà e nuovi frame

Nella codifica corrente il bit di utilità meno significativo indica le
annotazioni e quello più significativo indica una catena di proprietà. Prima
il parser interpretava come catena il bit delle annotazioni. Ora i due casi
sono distinti; viene mantenuto il comportamento specifico dei file legacy.

`Constants.java` aggiunge i frame `0x31` e `0x32` per domain e range delle
annotation property, con i corrispondenti lettori e scrittori.

### Reset e riferimenti

Il Reset era già presente nella PR. La correzione evita che l'azzeramento delle
tabelle di decodifica cancelli anche i prefissi dell'ontologia: sono informazioni
con finalità diverse. Il parser conserva i namespace riservati e scarta soltanto
le mappature selezionate dai bit NS/ID.

Un Reset all'inizio dello stream non viene più scambiato per un frame legacy.
Le declaration usano il controllo centralizzato degli identificativi e producono
`OWLParserException` per riferimenti non validi, invece di errori generici di indice
o omissioni silenziose. La scrittura rimane standard, senza generare Reset.

### Valori numerici

La specifica memorizza la frazione dei decimali compatti con le cifre invertite.
Prima il parser concatenava direttamente il numero letto: `1.61` poteva diventare
`1.16`. Ora ricostruisce la frazione nel verso corretto, anche per la forma con
esponente. I valori numerici in lettura usano `BigInteger` per evitare overflow
legati al limite di `int`; la scrittura zig-zag usa un intermedio `long`.

Il renderer usa la codifica numerica compatta soltanto quando conserva la forma
lessicale. Valori come `01` o `+1` non devono essere convertiti implicitamente in
`1` nel tentativo di risparmiare byte.

È stata inoltre corretta l'omissione del filler nelle cardinalità sulle data
property: solo il vero top datatype può essere implicito; `rdf:PlainLiteral`
non deve essere trattato come equivalente a quel top.

### Raccolta degli identificativi e misurazioni

La raccolta comprende anche le entità delle annotazioni, i data range annidati
e gli IRI presenti soltanto in domain/range delle annotation property. La firma
viene raccolta dall'ontologia corrente senza incorporare quella degli imports.
Le principali iterazioni sono ordinate, per rendere più riproducibile la codifica.
Il renderer termina lo stream con un frame End.

Sono state rimosse le liste statiche di debug, le stampe per frame e le scritture
di `actinium_trace.txt`: eseguire queste operazioni durante il parsing alterava
il lavoro misurato e tratteneva oggetti non necessari.

## 2. Elenco del dataset e percorsi riproducibili

Commit: `a7f1085` — `fix(dataset): usa un elenco esplicito delle 100 ontologie`.

File nuovi: `lib/src/test/java/benchmark/DatasetFiles.java` e
`benchmark/metadata.csv`.

Prima copertura, test e script avevano ricerche e percorsi distinti, alcuni
specifici del computer di un autore. Inoltre potevano saltare i file mancanti,
riducendo silenziosamente il numero di casi verificati.

`DatasetFiles` centralizza:

- individuazione della radice del progetto e del dataset;
- configurazione con DATASET_DIR e METADATA_FILE;
- compatibilità con `standard`/`std` e le estensioni della consegna;
- normalizzazione del nome base;
- lettura del CSV con campi eventualmente quotati;
- controllo dei duplicati e delle 100 ontologie attese;
- elenco dei file mancanti senza eliminarli dai casi di test.

Il metadata originale esterno al progetto contiene 16.555 righe ORE. Quello
incluso contiene le righe delle 100 ontologie effettivamente consegnate, senza
selezionarle sulla base dell'esito dei test. Le ontologie problematiche rimangono
nell'elenco. Durante il commit sono stati uniformati i terminatori di riga a LF;
i valori CSV sono invariati.

## 3. Test e copertura che verificano il contenuto completo

Commit: `1755b3b` — `test(roundtrip): verifica il percorso completo e aggiunge regressioni`.

File: `ProtocOWLTest.java`, `DatasetRoundTripTest.java`, nuovo
`BenchmarkComplianceTest.java`, `CoverageAnalyzer.java`, `lib/build.gradle.kts`.

### Percorso a/b/c

Prima il test dataset confrontava soltanto il numero degli assiomi nella fase a,
non eseguiva b e, in c, ripartiva dall'originale Functional. Due ontologie possono
avere lo stesso numero di assiomi e contenere informazioni diverse.

Ora ogni coppia ontologia/variante è un caso TestNG distinto e verifica:

1. a: binario del dataset -> OWLAPI; confronto con il Functional originale;
2. b: risultato di a -> Functional -> OWLAPI; confronto con lo stesso originale;
3. c: risultato di b -> ProtocOWL standard -> OWLAPI; confronto con lo stesso originale.

Il DataProvider prepara esattamente 200 casi e non scarta quelli con input
mancante. Gli errori indicano nome, variante e fase. Le differenze negli assiomi
continuano a essere riportate nel dettaglio.

`ProtocOWLTest.assertEquals` confrontava già IRI/version IRI, prefissi,
annotazioni dell'ontologia e insieme degli assiomi. È stato aggiunto il confronto
degli imports. Resta la normalizzazione circoscritta del prefisso di default
preesistente nel confronto; non vengono eliminati assiomi per far passare i test.
La diagnostica degli assiomi viene costruita soltanto quando gli insiemi differiscono.

### Configurazione OWLAPI

OWLAPI può aggiungere declaration o generare un prefisso di default durante la
scrittura Functional. Questi comportamenti sono stati disabilitati nel percorso
round-trip, usando il renderer Functional OWLAPI con prefissi espliciti.
Gli identificativi degli individui anonimi non vengono rimappati durante il
caricamento, così la verifica può confrontare gli identificativi preservati.

### Dimensioni e copertura

`roundtrip_sizes.csv` registra la dimensione prodotta dalla fase c per ogni caso
completato. Attualmente contiene 190 righe dati: i dieci casi falliti in a non
arrivano a c. Questa incompletezza è visibile e blocca la validazione finale.

CoverageAnalyzer esegue le 300 operazioni attese: due parsing binari e una
scrittura per ciascuna ontologia. Nella scrittura verifica anche la rilettura e
il confronto con il Functional originale. Scrive il report prima di segnalare
il fallimento e raggruppa gli errori per costrutto. Il successo del solo parsing
non prova l'uguaglianza con il Functional: questa è responsabilità del round-trip.

### Gradle e regressioni

Sono disponibili tre task separati: `test`, `coverageTest`, `datasetTest`.
Copertura e dataset vengono rieseguiti anche se Gradle li considererebbe aggiornati;
se richiesti insieme, la copertura precede il dataset. La compilazione usa release
Java 21 e i test hanno heap massimo di 4 GiB. I percorsi sono configurabili anche
con `-PdatasetDir=...` e `-PmetadataFile=...`.

Le nuove regressioni controllano assiomi prima omessi, annotazioni anche annidate,
catene, errore su SWRL, tutte le combinazioni Reset, Reset come primo frame,
identificatori invalidi, assenza di declaration inventate, frazioni invertite,
limiti numerici e forme lessicali.

## 4. Benchmark e validazione dei risultati

Commit: `d6f069e` — `fix(benchmark): valida tutte le combinazioni e le dimensioni della fase 2`.

File: `run_benchmark.sh`, `BenchmarkTask.java`, nuovi `BenchmarkResults.java` e
`BenchmarkResultsTest.java` in `lib/src/test/java/benchmark/`.

Lo script esegue le verifiche prima delle misure, usa il manifest condiviso e
uniforma la locale per la lettura delle metriche. Registra ambiente hardware/OS,
JDK, opzioni JVM, revisione e stato Git, oltre ai file effettivamente elaborati.

La JVM separata per ogni operazione, GNU time, il divieto di render MIS_128 e
l'esclusione della preparazione dal timer erano già previsti nel codice della
PR e sono stati mantenuti. BenchmarkTask aggiunge la validazione dei nomi dei
formati anche in parsing, usa i nomi condivisi e conserva gli identificativi anonimi.

`BenchmarkResults` verifica:

- intestazione, tutte le 500 combinazioni attese e assenza di duplicati;
- assenza di render MIS_128;
- tempi e MRSS numerici e validi;
- InputSizeBytes corrispondente al file e output nullo in parsing;
- output positivo in rendering e, per ProtocOWL, uguale alla fase 2 standard;
- formule di rapporto dimensionale e risparmio di spazio.

Il nuovo test del validatore prova un CSV corretto e cinque alterazioni: riga
mancante, duplicato, render vietato, dimensione output errata e dimensione input
errata. Sono prove del validatore: non sono misure reali di prestazioni.

## 5. Documentazione, risultati e limite ancora aperto

Il commit di documentazione che contiene questa guida aggiunge `BENCHMARK.md`,
`benchmark/known_dataset_issues.md` e `roundtrip_sizes.csv`, e aggiorna
`lib/coverage_report.csv`. Nel report di copertura la variante `std` è denominata
`standard`, coerentemente con il manifest condiviso.

Esiti delle verifiche del 24 settembre 2026:

| Verifica | Esito |
| --- | --- |
| Test rapidi | 22 superati |
| Copertura | 300 operazioni riuscite |
| Round-trip generati dai Functional | 100 riusciti, compresi nella copertura |
| Round-trip dei binari forniti | 190 riusciti, 10 falliti su 5 ontologie, nessuno saltato |
| Benchmark completo di prestazioni | Non rieseguito |

Il fallimento rimanente è documentato con un caso verificato nei byte:
`2.04` nel Functional contro `2.4` nel binario. È distinto dall'errore del parser
`1.61 -> 1.16`, che è stato corretto. I file del dataset non sono stati riscritti.
Il test rigoroso deve restare fallito finché i binari non sono coerenti con il
Functional. Serve la verifica del professore o di chi ha generato il dataset.

Il vecchio `benchmark_results.csv` e il relativo ambiente non sono nuove misure
del codice corretto. Il benchmark completo resta bloccato dalla fase 2; inoltre
GNU time non è disponibile sulla macchina usata per le verifiche.

## 6. Cosa compare nella storia Git

Prima della creazione dei commit, `review/pr-3` e `fix/benchmark-compliance`
puntavano entrambi a `06d4163`; le correzioni erano soltanto nella working tree.
La creazione del branch, da sola, non aveva registrato le modifiche.

Ora il branch di correzione contiene quattro commit di implementazione e un
quinto di documentazione/report, tutti successivi a `06d4163`. Ogni commit ha un
hash, l'autore e il committer configurati in Git, la data di creazione, un titolo,
un messaggio esplicativo e il diff. I commit originali del collega mantengono
hash e autori. Non sono state retrodatate le correzioni né ricostruiti tentativi
intermedi come se fossero stati committati allora.

Il branch `review/pr-3` continua a puntare alla versione iniziale della PR.
`main` non viene modificato dalla creazione di questi commit. Non è stato fatto
push: su GitHub la PR esistente non cambia finché i nuovi commit non vengono
pubblicati sul suo branch sorgente o integrati tramite un'altra PR.

Comandi da eseguire dalla radice `protocowl-owlapi`:

```bash
# Storia con collegamenti fra branch e commit
git log --graph --oneline --decorate --all

# Soltanto le correzioni, nell'ordine di applicazione
git log --reverse --format=fuller 06d4163..fix/benchmark-compliance

# Messaggio e modifiche esatte del commit codec
git show b0c0e6f

# Confronto complessivo con la versione del collega
git diff 06d4163..fix/benchmark-compliance
```

Per mantenere i singoli commit anche dopo l'integrazione in `main`, preferire un
merge che conservi la storia, per esempio “Create a merge commit” quando disponibile.
Uno squash produce invece un unico nuovo commit su `main`: i cinque passaggi non
restano separati nella sua linea di storia. Un rebase o un cherry-pick conserva
normalmente la separazione logica dei commit ma può modificarne gli hash.

Per la discussione con il professore si può seguire l'ordine dei cinque commit:
problema nel codec, elenco completo degli input, verifica effettiva del round-trip,
controlli delle misure, evidenza del problema residuo nei dati. Non va presentato
il progetto come interamente verde: le dieci differenze sono parte dei risultati.
