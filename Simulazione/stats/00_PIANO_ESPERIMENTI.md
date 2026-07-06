# Piano degli esperimenti — Farmacia Gollmann

Ordine **logico** (per dipendenze metodologiche) delle 6 attività, struttura cartelle e mappatura
output `.dat` → script Python → grafici. Generato come predisposizione: **niente eseguito**.

> ⚠️ Una sola differenza rispetto alla lista iniziale dell'utente: **il Transitorio è spostato in
> testa** (era ultimo). Motivo: è il prerequisito di tutto ciò che è steady-state — certifica la
> convergenza (nessun ρ≥1) e **stima il warm-up** che la batch calibration (02) e l'infinito
> (05) DEVONO scartare per eliminare il bias iniziale. Coerente con CLAUDE.md §10 ("transitorio
> PRIMA degli esperimenti"). Se preferisci tenerlo in coda come step di sola presentazione, si può,
> ma allora 02/05 useranno il warm-up fisso `warmUpJobs` senza conferma empirica.

## Ordine logico e dipendenze

```
01 Transitorio ─┬─> 02 Batch calibration ─┐
                │                          ├─> 05 Infinito per-fascia + ConfigurationSearch ─┐
                └─> 03 Inventory config ───┤                                                 │
                          │                └─> 04 Baseline finito (config piena)              │
                          │                          │                                        │
                          └──────────────────────────┴────> 06 Ottimizzato finito + confronto<┘
```

- **01 → 02**: la batch calibration scarta il warm-up stimato dal transitorio.
- **01 → 03/05**: l'infinito (config search e inventory, se valutati a regime) usano lo stesso warm-up.
- **02 → 05**: l'infinito per-fascia usa il `(b,k)` calibrato (Chatfield).
- **03 → 04/05/06**: la `(s,S)` ottimale iniziale è **fissata** e usata (a parità di inventario) in
  tutte le run successive.
- **05 → 06**: la run finita ottimizzata usa lo staffing per-fascia trovato dalla config search.
- **04 ↔ 06**: confronto baseline vs ottimizzato (stesso inventario, stessa metodologia).

## Struttura cartelle (`Simulazione/stats/`)

Una cartella per attività; in ciascuna `dat/` (statistiche) e `grafici/` (PNG dagli script Python).

```
stats/
  00_PIANO_ESPERIMENTI.md      <- questo file
  01_transitorio/        { dat/  grafici/ }
  02_batch_calibration/  { dat/  grafici/ }
  03_baseline_finito/    { dat/  grafici/ }   <- baseline PRIMA (dimostra il problema)
  04_inventory_config/   { dat/  grafici/ }
  05_infinito_configsearch/ { dat/ grafici/ }
  06_ottimizzato_finito/ { dat/  grafici/ }
```

> ⚠️ **Ordine aggiornato**: 03 = **baseline finito** (config piena, s naive 70% di S) PRIMA, per
> fotografare costo alto + P(OOS) fuori SLA; poi 04 = inventory config (ottimizza s). La baseline NON
> usa la (s,S) ottimizzata: usa quella naive — è il "prima" del confronto.

> **Routing degli output — IMPLEMENTATO:** `FiniteHorizonSimulation` e l'infinito
> (`Simulation.writeBatchDatFiles` / `writeLevelTimeSeries`) leggono `-Dstats.dir`. Esempio:
> `-Dstats.dir=stats/03_baseline_finito/dat`. Default = vecchie cartelle piatte (`stats/finite`,
> `stats/infinite`). `TransientAnalysis` scrive già in `01_transitorio/dat/`. (Da fare se servirà:
> stesso knob in `InventoryConfigSearch`/`ConfigurationSearch`.)

## Statistiche per-centro da raccogliere (tutte le attività)

Per ogni centro cliente { **Casse**, **Cassa Online**, **Dispatcher**, **Braccio Uno**, **Braccio
Due**, **Casse Pagamento** } e per il **Magazziniere**:
`utilizzazione ρ`, `E[T]` (response/wait nel nodo), `E[Tq]` (delay in coda), `E[N]`, `E[Nq]`,
`throughput X`, `tempo medio di servizio`. + per i bracci: loss/OOS per-articolo.

Di sistema: **response time end-to-end** (clienti fisici e online), `P(OOS)` per-articolo,
**costo inventario** (holding+shortage+order, per giornata), **delivery lag** (medio/%>30/%>60 min),
**costo personale/totale**. `P(abbandono)` = 0 (abbandoni disabilitati, CLAUDE.md §18).

Inventory per classe (1..5): `livello medio time-avg`, `OOS`, `setup` (n. ordini), `unità ordinate`,
**check di flusso** (ordinate≈domandate), e **serie temporale per ESTRAZIONE** in
`inventory_class<k>_level.dat` (colonne `time, level, cum_oos`): livello a denti di sega + OOS cumulati
→ grafico di come si comporta l'inventario nel tempo e quando va in stockout. Fonte: run FINITA (una
giornata, `recordHistory=true`, ~2400 estrazioni — l'infinito ha `recordHistory=false` anti-leak).

---

## 01 — Analisi del transitorio
- **Obiettivo**: verificare la convergenza (nessuna divergenza ρ≥1) e **stimare il warm-up** (n.
  completamenti / tempo dopo cui le statistiche si stabilizzano). Concorda col flusso forzato.
- **Dipendenze**: nessuna. Config = piena (5 casse), `(s,S)` di default, **λ dell'infinito**
  (costante; allineare λ transitorio/infinito, issue §13.5).
- **Comando**: `java -cp build_cmp it.farmacia.control.TransientAnalysis`
- **Output .dat** → `01_transitorio/dat/`: `transient_responseTime.dat`, `transient_utilCasse.dat`,
  `transient_numInSystem.dat` (una colonna per replica).
- **Script** → `01_transitorio/grafici/`: `plot_transient.py`, `transitorio.py`.
- **Criterio**: curve che si appiattiscono; warm-up < orizzonte. Output chiave: **warmUpJobs** da
  usare in 02/05.

## 02 — Batch calibration (Chatfield)
- **Obiettivo**: scegliere `(b, k)` (batch size, n. batch) per l'orizzonte infinito così che le
  batch mean siano ~indipendenti: **Chatfield `|r1| < 2/√k = 0.25` con k=64**.
- **Dipendenze**: 01 (warm-up).
- **Comando**: `java -Xmx4g -cp build_cmp it.farmacia.control.BatchCalibration`
- **Output .dat** → `02_batch_calibration/dat/`: serie di autocorrelazione per centro/globale
  (oggi `*_autocorrelation.dat` + temp `chatfield_*.dat`; salvare qui), tabella `(b,gap,r1)`.
- **Script** → `02_batch_calibration/grafici/`: `batch_plot.py`, `batch_over_plot.py`,
  `plot_batch_infinite.py`.
- **Criterio**: minimo `b` (e/o `gap`) che dà `|r1|<0.25` su response globale. Output chiave: **b, gap**.

## 03 — Inventory config search (politica (s,S) iniziale)
- **Obiettivo**: trovare la `(s,S)` ottimale **iniziale** (S fisse, ottimizza s) che minimizza il
  costo inventario sotto `P(OOS) ≤ 5%`. Resta **fissa** in 04/05/06 (a parità di inventario).
- **Dipendenze**: 01 (stabilità). **Indipendente da 02**: valuta a orizzonte FINITO (repliche +
  CRN, non batch means) → non usa il batch size. L'inventario non sposta Chatfield (separazione di
  scale: 1 batch ≈ 76 h media su ~76 cicli di riordino orari). Config casse = piena.
- **Comando**: `java -Xmx4g -cp build_cmp it.farmacia.control.InventoryConfigSearch`
- **Output .dat** → `03_inventory_config/dat/`: `results.dat` (tutte le config provate),
  `best_configs.dat` (top-10).
- **Script** → `03_inventory_config/grafici/`: `plot_inventory.py`.
- **Criterio**: costo inventario minimo con `P(OOS) ≤ 5%` per ogni classe. Output chiave: **s\* per
  classe**.

## 04 — Baseline finito (configurazione piena)
- **Obiettivo**: statistiche e **costi di riferimento** della gestione attuale (5 casse sempre
  aperte, magazziniere baseline, `(s,S)` da 03). Giornata NSPP reale, 128 repliche, IC t-Student.
- **Dipendenze**: 03 ((s,S)). 01 (stabilità confermata).
- **Comando**: `java -Xmx4g -Dreps=128 -cp build_cmp it.farmacia.control.FiniteHorizonSimulation`
- **Output .dat** → `04_baseline_finito/dat/`: `<centro>_{utilization,avgWait,avgDelay,avgNode,
  avgQueue,avgInterarrivals}.dat`, `inventory_class<k>_level.dat`, riepilogo costi/SLA.
- **Script** → `04_baseline_finito/grafici/`: `plot_finite_horizon.py`, `plot_inventory.py`,
  `time_series_plotter.py`, `sampling_plot.py`, `interarrival_plot.py`.
- **Criterio**: registrare `E[T]` end-to-end **per fascia**, `P(OOS)`, costi → **riferimento** per il
  confronto e per fissare poi il SLO response (decisione rinviata §18).

## 05 — Infinito per-fascia + Configuration search (staffing ottimale per fascia)
- **Obiettivo**: per **ogni fascia oraria** (λ costante della fascia), catturare via `ConfigurationSearch`
  le metriche di **ogni** combinazione di staffing candidata (n. casse, n. magazzinieri) in regime
  steady-state (batch means): `E[T]`, `P(OOS)`, throughput, ρ per centro, costo (lavoro+inventario).
  ⚠️ **Riscritta in CLAUDE.md §21.6/§23**: NIENTE score pesato e NIENTE SLO a soglia fissa — si
  sceglie lo staffing **a valle**, dal grafico di Pareto costo–E[T] per fascia (come per l'inventario,
  §21.1), col tempo di risposta come asse di trade-off. Politica di inventario FISSA alla config
  scelta in 03/04 (`s={75,65,55,40,30}`).
- **Dipendenze**: 01 (warm-up), 02 ((b,k)), 03 ((s,S) fissa).
- **Comando**: `java -Xmx6g -Dnb=64 -Dbs=<b> -cp build_cmp 'it.farmacia.control.ConfigurationSearch$Staffing05'`
  (default `-Dnb=64 -Dbs=1024`; `<b>` = batch size calibrato in 02 se diverso).
- **Output .dat** → `05_infinito_configsearch/dat/`: `results.dat` (una riga per fascia×config:
  E[T], P(OOS), throughput, costo lavoro/inventario/totale, ρ per i 7 centri, split Braccio Uno
  HIGH/LOW), `_run_info.txt`.
- **Script** → `05_infinito_configsearch/plot_configsearch.py`: griglia 3×4 (una per fascia),
  fronte di Pareto costo–E[T] annotato `nCasse,nMag`, overlay opzionale dello staffing scelto.
- **Criterio**: scelta manuale dal fronte di Pareto per fascia (nessuna soglia automatica). Output
  chiave: **tabella staffing per fascia** (da riportare come `staffing` in `FiniteHorizonSimulation`
  attività 06).

## 06 — Ottimizzato finito
- **Obiettivo**: rieseguire l'orizzonte finito (giornata NSPP, 128 rep) con lo **staffing per-fascia
  di 05** (letto a runtime da `best_staffing.tsv`) e la `(s,S)` di 03/04; statistiche + costi.
- **Dipendenze**: 05 (staffing), 03/04 ((s,S)), baseline 03 (riferimento).
- **Comando**: `java -Xmx4g -Dreps=128 -cp build_cmp 'it.farmacia.control.FiniteHorizonSimulation$Ottimizzato'`
- **Output .dat** → `06_ottimizzato_finito/dat/`: stessi file di 03 (per confronto 1:1) +
  `summary.dat` (KPI aggregati + costi personale/inventario/totale, per l'attività 08).

## 07 — Ottimizzato + TURNI REALISTICI finito
- **Obiettivo**: come 06 ma con lo staffing = **copertura dei turni contigui realistici** generati
  da `turni_cassieri.py` (attività 05→turni; letto a runtime da `turni_generati_search.dat`).
  Copertura ≥ staffing ottimo per costruzione → misura il **"costo del realismo"** dei vincoli di
  turnazione (turni min 3h / max 8h).
- **Dipendenze**: 05 + `turni_cassieri.py` (genera `turni_generati_search.dat`).
- **Comando**: `java -Xmx4g -Dreps=128 -cp build_cmp 'it.farmacia.control.FiniteHorizonSimulation$Ottimizzato_Turni'`
- **Output .dat** → `07_ottimizzato_turni_finito/dat/`: stessi file di 06 + `summary.dat`.

## 08 — CONFRONTO FINALE a 3 scenari (Baseline vs Ottimizzato vs Ottimizzato+turni)
- **Obiettivo**: quadro conclusivo per la relazione — per ogni statistica per-centro l'overlay delle
  3 curve sulla giornata, e i **conti dei costi a 3** (personale+inventario impilati con Δ% vs
  baseline, P(OOS) vs SLA 5%, E[T] sistema).
- **Dipendenze**: 03, 06, 07 (tutti con `summary.dat`, quindi rieseguiti col codice corrente).
- **Comando**: `python stats/08_confronto_finito/confronto_scenari.py`
- **Output** → `08_confronto_finito/grafici/`: `<centro>_<Metrica>.png` (overlay 3 curve, ~58 file),
  `confronto_costi.png` (3 pannelli) + `confronto_costi.dat` (tabella riassuntiva).
- **Criterio**: **costo totale ridotto vs baseline** a parità di SLA (P(OOS)≤5%) e **prestazioni non
  peggiorate** (response ≤ baseline / dentro il SLO fissato coi dati raccolti).

---

### Note operative
- Le run pesanti (04/05/06 a 128 rep / 64 batch) le esegue l'utente; in sandbox solo run leggere di
  verifica. Niente commit/merge senza ok (CLAUDE.md §15).
- Aggiornare il SLO sul tempo di risposta **dopo** aver raccolto 04 (baseline) e 06 (ottimizzato),
  ancorandolo ai valori osservati (decisione rinviata, CLAUDE.md §18).
