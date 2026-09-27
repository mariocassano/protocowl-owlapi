# Differenze nei file del dataset fornito

I controlli a/b/c sono rigorosi: nessun assioma, prefisso o annotazione viene
rimosso dal confronto e nessuna ontologia viene esclusa. Dopo la correzione del
parser rimangono 10 casi falliti nella fase a, relativi a queste cinque ontologie
(in entrambe le varianti standard e MIS_128):

| Nome base | Assiomi differenti per variante |
| --- | ---: |
| kb-owl-syntax-unraveled-depth-2-km-relevant-cardinalities-disjointness-constant-classes-value-nominals.ofn | 6 |
| kb-owl-syntax-unraveled-depth-3-km-relevant-cardinalities-disjointness-triggers-constant-classes-value-nominals.ofn | 9 |
| kb-owl-syntax-unraveled-depth-3-triggers-constant-classes-value-classes.ofn | 9 |
| kb-owl-syntax-unraveled-depth-4-disjointness-triggers-constant-classes-value-nominals.ofn | 14 |
| kb-owl-syntax-unraveled-depth-4-km-relevant-cardinalities-disjointness-triggers-constant-classes-value-classes.ofn | 14 |

## Esempio verificato a livello di byte

Nel primo file della tabella, `functional/*_functional.owl` contiene per Boron
un DataHasValue di `the-cardinal-value` pari a `"2.04"^^xsd:float`.
Il corrispondente `protocowl/std/*_protocowl.owl` ha SHA-256:

```
b20f58de81bb17efb5ffb9a2172a704537b28a9acf43d6f4f9c7f88214db2cd4
```

All'offset 157587 (contato da zero), il literal inizia con:

```
16 04 04
```

- `16`: literal tipizzato, formato fixed point (5).
- primo `04`: SVarInt zig-zag, parte intera = 2.
- secondo `04`: VarInt della parte frazionaria invertita = 4.

Secondo la sezione 3 della specifica, la frazione `.04` deve essere memorizzata
come `40`, cioè byte `28`, e non come `4`. I byte forniti codificano `2.4`.
Il parser non può dedurre lo zero mancante senza consultare il Functional
originale o introdurre correzioni specifiche per ontologia, entrambe soluzioni
inappropriate per un decoder generico.

Analogamente emergono `3.04 -> 3.4`, `2.05 -> 2.5`, `2.02 -> 2.2`, `2.01 -> 2.1`.
Non si può aggiungere uno zero a ogni frazione a una cifra: il Functional della
stessa ontologia contiene anche cinque occorrenze legittime di `2.2`.

## Azione necessaria prima della consegna

Chiedere al professore la verifica e rigenerazione delle due varianti binarie di
queste ontologie, a partire dal Functional che fa fede. Non modificare il
Functional, non rimuovere queste ontologie e non rendere permissivo il confronto.
La produzione MIS_128 resta fuori dallo scope del renderer del progetto.

`datasetTest` rimane intenzionalmente rosso e il benchmark si arresta prima delle
misurazioni finché i dati non sono corretti. I report TestNG mostrano le differenze
complete, con nome, variante e fase. Le altre 190 coppie completano a, b e c.
Il controllo di copertura verifica separatamente anche Functional -> renderer
corrente -> parser: questo consente di distinguere difetti del nostro output da
differenze già presenti nei binari forniti.

## Verifiche locali del 24 settembre 2026

Ambiente: macOS, JDK 24, Gradle wrapper 9.0.0, heap dei test fino a 4 GiB.

- 22 test rapidi superati (codec, regressioni e validatore CSV).
- Copertura: 300/300 operazioni riuscite. Per tutte le 100 scritture è stato
  verificato anche il confronto completo Functional -> ProtocOWL -> OWLAPI.
- Dataset a/b/c: 200 casi eseguiti, 190 superati, 10 falliti nella fase a come
  documentato sopra; nessun caso saltato.
- CLI render ProtocOWL_128: errore esplicito con codice di uscita 1.
- Prova di render standard su 00022.owl: 4000 byte, uguali alla misura della
  fase 2 per entrambe le varianti.
- Sintassi Bash e `git diff --check`: verificati.

Il benchmark completo da 500 JVM non è stato rieseguito: oltre al blocco sui
binari, questa macchina non dispone di GNU time. I CSV e l'ambiente del benchmark
precedente non sono stati spacciati per risultati delle correzioni.

## Verifica rigorosa dei prefissi, 26 settembre 2026

Il parser non sintetizza più un alias dal percorso del namespace e il confronto
non aggiunge più un default mancante. Le 190 coppie del dataset prima valide
continuano a completare a/b/c con confronto esatto dei prefissi; rimangono le
stesse dieci differenze sui decimali.

È emersa un'ulteriore incoerenza nei riferimenti di base, fuori dal dataset ORE:
`lib/src/test/resources/pizza.owl` dichiara il prefisso `pizza:`, ma
`pizza.oprt` dichiara solo default, `dc:`, `terms:` e `skos:` oltre ai cinque
prefissi impliciti. In precedenza `addConventionalAlias` ricostruiva `pizza:`
dal percorso `/pizza/pizza.owl#`, nascondendo la differenza. Questa euristica
aggiungeva anche alias inesistenti ad altri documenti e perciò è stata rimossa.

Il test `testPizzaOntology` ora fallisce nel confronto col binario fornito; il
round-trip del Functional tramite il renderer corrente viene verificato prima
e passa, conservando l'alias esplicito. Serve un riferimento binario che conservi
anche questo prefisso, oppure un chiarimento del contratto da parte del docente.
Il progetto non modifica i riferimenti e non rende permissivo il confronto.

Esiti aggiornati: 35/36 casi rapidi superati (tutte le 14 nuove regressioni passano),
300/300 operazioni di copertura riuscite, 190/200 round-trip riusciti senza skip.
I cinque test isolati dello script passano. Il benchmark completo non è stato
eseguito: i prerequisiti non sono superati e GNU time non è disponibile localmente.
I risultati storici si trovano ora in `historical/2026-09-20/`.
