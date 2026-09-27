# Misure storiche, non risultati della versione corrente

Questi due file sono stati archiviati senza modificarne i byte. La data della
cartella deriva da `TimestampUTC` nel rapporto dell'ambiente originale.

- `benchmark_results.csv`: campagna precedente, 500 righe.
- `benchmark_environment.txt`: macchina, JDK e opzioni della campagna precedente.

Il codec è cambiato dopo queste misure: per esempio il render di `00022.owl`
produce ora 4000 byte, mentre il CSV storico ne registra 3999. Il numero di
righe corretto non prova né la validità del contenuto né l'attualità dei tempi
misurati. Non correggere a mano le dimensioni lasciando invariati tempi e MRSS:
la campagna deve essere rieseguita dopo il superamento della validazione.

Il percorso di output attuale resta `benchmark_results.csv` alla radice del progetto. `run_benchmark.sh` pubblica
CSV e ambiente solo dopo i prerequisiti e la validazione finale. Un fallimento
non sovrascrive l'ultima campagna riuscita.

SHA-256 originali:

```
746724d52fcdefd2aa1b2a6703813e86ef3e7a6e82cc9f013500f9cfefea44c1  benchmark_results.csv
51590f3fd12e50ade8a3693a5466308e6139b8411c5d9354c44a0d97344a83f6  benchmark_environment.txt
```
