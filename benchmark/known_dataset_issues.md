# Chiarimenti sui materiali e validazione finale

## Fixed-point negativi: diagnosi precedente superata

Il docente ha chiarito che nel formato fixed point 5 la parte intera di un
valore negativo viene diminuita di uno **prima** della codifica SVarInt.
Questo permette di distinguere anche `-0.5` da `0.5`.

La lettura corretta è:

1. decodificare SVarInt normalmente, ottenendo `s`;
2. se `s < 0`, ricostruire la parte testuale come `"-" + abs(s + 1)`;
3. altrimenti usare `s`;
4. aggiungere il punto e le cifre della frazione, invertite come previsto.

Il segno deve restare separato: `s=-1` restituisce `-0`, non `0`.
Zig-Zag non cambia; si applica una convenzione aggiuntiva alla componente
intera del decimal. La compensazione non va applicata globalmente a SVarInt
né all'esponente del formato 6. La modalità storica del parser rimane esplicita.

| Literal | Parte intera prima di SVarInt | Payload Zig-Zag | Frazione | Byte iniziali |
| --- | ---: | ---: | ---: | --- |
| 0.5 | 0 | 0 | 5 | `16 00 05` |
| -0.5 | -1 | 1 | 5 | `16 01 05` |
| -1.0 | -2 | 3 | 0 | `16 03 00` |
| -3.0 | -4 | 7 | 0 | `16 07 00` |
| 2.04 | 2 | 4 | 40 | `16 04 28` |
| -2.04 | -3 | 5 | 40 | `16 05 28` |

La precedente segnalazione dei nuovi binari come errati era quindi dovuta a
un'interpretazione incompleta nel decoder. **I byte di Benzoate e Azide sono
corretti secondo il chiarimento del docente.** Il codice è stato corretto, non
il dataset, e sono state aggiunte regressioni per segno dello zero, frazioni
con zeri e componenti su più byte.

## Evidenze e verifica finale

- 80/80 test rapidi superati, zero skip.
- 300/300 operazioni di copertura riuscite.
- 200/200 round-trip completi a/b/c, zero skip o ontologie escluse.
- 200 dimensioni nella fase 2, coincidenti fra standard e MIS_128 per ciascuna ontologia.

I nuovi input mantengono gli stessi SHA-256 verificati in precedenza. Le vecchie
frazioni positive erano già state corrette dal docente nella nuova distribuzione.
Nessun file del dataset è stato modificato dal nostro intervento.

Verifica indipendente dei byte, dalla radice del progetto:

```bash
python3 benchmark/revision_2026-09-30-clarified/verify_dataset_literals.py ../dataset_onto
```

La verifica ora restituisce exit 0 e sei esempi coerenti, in entrambe le varianti.
Le evidenze correnti sono in
[revision_2026-09-30-clarified](revision_2026-09-30-clarified/README.md).
La cartella `revision_2026-09-30` conserva la diagnosi precedente, esplicitamente
marcata come **superata**, e non descrive lo stato attuale.

## Prefisso pizza

Il docente ha confermato che il suo encoder, usando una mappa indicizzata per
namespace, perde uno dei due alias `:` e `pizza:`. Ha autorizzato a ignorare
questo caso nel task. `assertEqualsPizzaReference` ammette soltanto tale alias
mancante nel riferimento storico, quando è duplicato del namespace predefinito.
Un prefisso errato o qualsiasi altra differenza continuano a causare errore.
Il dataset e il round-trip del nostro renderer usano il confronto esatto;
il parser non inventa prefissi e il renderer conserva entrambi gli alias.
