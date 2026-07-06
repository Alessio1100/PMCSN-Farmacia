package it.farmacia.control;

import it.farmacia.utils.Rvms;

import java.io.*;
import java.util.*;

/**
 * Attività 05 del piano esperimenti (stats/00_PIANO_ESPERIMENTI.md): ricerca dello STAFFING
 * (n. casse, n. magazzinieri) per ciascuna delle 12 fasce orarie, a ORIZZONTE INFINITO
 * (regime, batch means) — l'analogo di {@link InventoryConfigSearch} ma per il personale.
 *
 * Riscritta secondo CLAUDE.md §21.6: la versione precedente era incoerente con lo scope corrente:
 *  1. il costo era dominato da "perdita per abbandono" (abbandonanti/h × 45€), ma gli ABBANDONI
 *     sono DISABILITATI (§18) → termine morto, mai diverso da zero;
 *  2. usava tariffe di lavoro inventate (15€/h cassa, 10€/h magazziniere) invece di quelle di
 *     progetto (§8: farmacista 26 €/h, magazziniere 18 €/h);
 *  3. usava uno SCORE unico con un prezzo/farmaco inventato invece del costo di inventario
 *     coerente già usato da {@link InventoryConfigSearch} (§20.3: holding/shortage/order da
 *     valore unitario del farmaco).
 *
 * METRICA (niente score con pesi arbitrari, coerente con §20.7 "min costo sotto SLA"): TUTTO in €.
 *
 *     C_tot [€/gg-eq] = C_lavoro (§8) + C_inventario (holding+shortage+order, §20.3) + C_attesa
 *     C_attesa = c_w · N_clienti_gg · E[T]_ore      (per Little ≡ c_w · E[N] · 12h)
 *
 * Il trade-off P(OOS)/tempo di risposta/costo è risolto per MONETIZZAZIONE: l'OOS è già prezzato
 * (shortage cost dentro C_inventario), lo staffing è prezzato (tariffe §8), e il tempo di risposta
 * viene prezzato col costo d'attesa c_w (valore del tempo del cliente, {@code -Dcost.wait}, def
 * 10 €/h — UNICA assunzione nuova, sensitivity banale variando il knob). Così una config "min OOS
 * ma E[T] enorme" non può mai vincere (C_attesa la annienta) senza bisogno di soglie artificiali
 * sul tempo di risposta. Il P(OOS) ≤ {@code -Dsla.oos} (def 5%) resta VINCOLO HARD primario (§3):
 * la metrica sceglie solo tra le config che lo rispettano.
 *
 * Per ogni combinazione (fascia, nCasse, nMag) si CATTURANO comunque TUTTE le metriche
 * (E[T]_sistema, P(OOS), throughput, ρ per centro, scomposizione dei costi) su .dat: il grafico
 * (scomposizione a barre + fronte di Pareto, plot_configsearch.py) resta lo strumento di verifica
 * della scelta (§21.6).
 *
 * Politica di inventario: FISSA alla configurazione scelta in attività 04 (s={75,65,55,40,30},
 * R=30 min, §21.1) — qui si ottimizza SOLO lo staffing a parità di inventario; l'OOS va poi
 * ri-verificato con lo staffing scelto (il lead time è endogeno e cresce se si tagliano le casse,
 * §19), cosa che fa naturalmente la colonna P(OOS) di questo stesso output.
 *
 * Costo: espresso come €/giorno-operativo "equivalente" (proietta la λ costante della fascia su
 * un'intera giornata da {@link #OPERATING_HOURS_PER_DAY} ore, stessa base usata da
 * {@link InventorySystem#calculateTotalCost} e da §8) — usato SOLO per il ranking relativo tra le
 * configurazioni della STESSA fascia (stessa λ, stessa base temporale per tutti i termini):
 * non è una stima del costo assoluto giornaliero (che richiederebbe comporre le 12 fasce con le
 * rispettive λ, esercizio lasciato al confronto finale in attività 06).
 *
 * A FINE RUN stampa per ogni fascia la config a min C_tot sotto la SLA (con la scomposizione
 * lavoro/inventario/attesa), il costo personale reale €/giorno, la tabella staffing 24-slot pronta
 * da incollare in {@code FiniteHorizonSimulation.Ottimizzato} (attività 06) e
 * {@code best_staffing.tsv} (consumabile da {@code plot_configsearch.py --staffing}).
 *
 * Output: {@code stats/05_infinito_configsearch/dat/results.dat} (una riga per fascia×config) +
 * {@code _run_info.txt} + {@code best_staffing.tsv}. Override: {@code -Dstats.dir}, {@code -Dnb}
 * (batch, def 64), {@code -Dbs} (batch size, def 1024), {@code -Dsla.oos}, {@code -Dcost.wait}.
 */
public class ConfigurationSearch {

    // ── Parametri batch means (orizzonte infinito) ────────────────────────────
    // Default = configurazione CALIBRATA dall'attività 02 (BatchCalibration a λ=0.0190975,
    // λ di produzione dell'infinito): k=64, b=1024, gap=0 — Chatfield max|r1|=0.194 < 0.25
    // con conferma multi-seed (vedi stats/02_batch_calibration/dat/_run_info.txt).
    private static final int NUM_BATCHES = Integer.getInteger("nb", 64);
    private static final int BATCH_SIZE  = Integer.getInteger("bs", 1024);

    // ── Politica di inventario FISSA (scelta in attività 04, §21.1) ───────────
    private static final int[] INVENTORY_CONFIG = {75, 65, 55, 40, 30};

    // ── Tariffe di lavoro (§8) ─────────────────────────────────────────────────
    private static final double COST_CASSA_PER_HOUR     = 26.0;  // € / farmacista / ora
    private static final double COST_MAG_PER_HOUR       = 18.0;  // € / magazziniere / ora
    private static final double OPERATING_HOURS_PER_DAY = 12.0;  // 08:00-20:00

    // ── SLA di servizio (vincolo HARD primario, §3/§18) — knob -Dsla.oos ──────
    // La ricerca resta senza soglie (scrive TUTTE le config); questa serve alla selezione finale.
    private static final double SLA_OOS = Double.parseDouble(System.getProperty("sla.oos", "0.05"));

    // ── Costo d'attesa: monetizzazione del tempo di risposta (knob -Dcost.wait) ──
    // METRICA UNICA in €/gg-eq (niente score con pesi arbitrari, coerente con §20.7 "min costo"):
    //     C_tot = C_lavoro + C_inventario(holding+shortage+order) + C_attesa
    //     C_attesa = c_w · N_clienti_gg · E[T]_h      (≡ c_w · L · τ per Little, L = λ·E[T])
    // c_w = valore del tempo del cliente in €/h — l'UNICA assunzione nuova. Default 10 €/h
    // (letteratura value-of-time: ~30–50% del salario orario lordo per attese/shopping).
    // Proprietà chiave: una config instabile (E[T] → enorme) ha C_attesa esplosivo e non può
    // vincere MAI, senza bisogno di un guardrail a soglia; una config sotto-scorte ha già lo
    // shortage cost dentro C_inventario (§20.3). Tutto commensurabile in €. Sensitivity su c_w
    // banale rilanciando con -Dcost.wait diverso.
    private static final double WAIT_COST_PER_CLIENT_HOUR =
            Double.parseDouble(System.getProperty("cost.wait", "10"));

    // ── Check di STABILITÀ (feasibility, insieme alla SLA OOS) ────────────────
    // Una config che non regge la domanda offerta (throughput X < 90%·λ) NON è feasible per
    // definizione: il sistema non ha stato stazionario, le batch means non stimano nulla di
    // sensato e il suo P(OOS) "basso" è un ARTEFATTO (la domanda resta bloccata in coda alle
    // casse e non arriva mai a bracci/inventario). Senza questo filtro, ai picchi la SLA OOS
    // verrebbe "soddisfatta" proprio dalle config instabili.
    private static final double STABILITY_MIN_X_RATIO = 0.90;

    // ── Spazio di ricerca staffing ─────────────────────────────────────────────
    // 2 casse = minimo operativo; 1 cassa è instabile per qualunque λ del range considerato.
    private static final int CASSE_MIN = 2, CASSE_MAX = 5;
    private static final int MAG_MIN   = 1, MAG_MAX   = 2;

    private static final String[] HOUR_LABELS = {
        "08-09", "09-10", "10-11", "11-12", "12-13", "13-14",
        "14-15", "15-16", "16-17", "17-18", "18-19", "19-20"
    };

    private String outDir        = System.getProperty("stats.dir", "stats/05_infinito_configsearch/dat");
    private String scenarioLabel = "05_STAFFING_SEARCH";
    private String[] centerNamesForInfo; // nomi effettivi dei centri (da RunResult), per _run_info.txt

    // ═══════════════ PUNTO D'INGRESSO (pattern annidato, come FiniteHorizonSimulation) ═══════════════
    // In IntelliJ compare come target eseguibile separato (ConfigurationSearch.Staffing05).
    public static class Staffing05 {
        public static void main(String[] args) {
            ConfigurationSearch s = new ConfigurationSearch();
            s.scenarioLabel = "05_STAFFING_SEARCH";
            s.outDir        = System.getProperty("stats.dir", "stats/05_infinito_configsearch/dat");
            s.run();
        }
    }

    /** Entry point legacy: esegue la ricerca staffing standard. */
    public static void main(String[] args) { Staffing05.main(args); }

    // ── Struttura risultato per una coppia (fascia, config) ───────────────────
    private static class Result {
        String hourLabel; double lambda;
        int numCasse, numMag;
        double sysRT, oosProb, abandonProb, throughput;
        double laborCostDay, invCostDay, waitCostDay, totalCostDay;
        double[] utilPerCenter;
        double utilB1High, utilB1Low;
    }

    public void run() {
        new File(outDir).mkdirs();

        // λ orarie rappresentative = media dei due slot da 30' del profilo NSPP CAPATO corrente
        // (Simulation.arrivalRates, §20.1) — non un array locale scollegato, così le fasce
        // corrispondono esattamente a quelle usate da FiniteHorizonSimulation/attività 06.
        double[] arrivalRates = new Simulation().arrivalRates;
        int numHours = HOUR_LABELS.length;
        double[] hourlyLambdas = new double[numHours];
        for (int h = 0; h < numHours; h++) {
            hourlyLambdas[h] = (arrivalRates[2 * h] + arrivalRates[2 * h + 1]) / 2.0;
        }

        int configsPerHour = (CASSE_MAX - CASSE_MIN + 1) * (MAG_MAX - MAG_MIN + 1);
        int total = numHours * configsPerHour;

        System.out.println("=".repeat(78));
        System.out.println("  RICERCA STAFFING PER FASCIA ORARIA — orizzonte infinito (batch means)");
        System.out.println("=".repeat(78));
        System.out.printf("  Ore=%d  config/ora=%d  run totali=%d  |  batch=%dx%d  |  inventario s=%s (fisso)%n",
                numHours, configsPerHour, total, NUM_BATCHES, BATCH_SIZE, Arrays.toString(INVENTORY_CONFIG));
        System.out.printf("  Tariffe: cassa %.0f€/h, magazziniere %.0f€/h (§8)  |  staffing casse[%d..%d] mag[%d..%d]%n%n",
                COST_CASSA_PER_HOUR, COST_MAG_PER_HOUR, CASSE_MIN, CASSE_MAX, MAG_MIN, MAG_MAX);

        String outPath = outDir + "/results.dat";
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outPath, false))) {
            bw.write("# hour\tlambda\tnumCasse\tnumMag\tE_T_s\tP_OOS\tP_aband\tthroughput_s\t"
                    + "laborCost_eur_day\tinvCost_eur_day\twaitCost_eur_day\ttotalCost_eur_day\t"
                    + "util_Casse\tutil_CassaOnline\tutil_Dispatcher\tutil_BraccioUno\tutil_BraccioDue"
                    + "\tutil_Magazziniere\tutil_Pagamento\tutil_B1_high\tutil_B1_low");
            bw.newLine();
        } catch (IOException e) {
            System.err.println("Errore file: " + e.getMessage());
            return;
        }

        List<Result> all = new ArrayList<>();
        int idx = 0;
        long startMs = System.currentTimeMillis();

        for (int h = 0; h < numHours; h++) {
            double lambda = hourlyLambdas[h];
            String label  = HOUR_LABELS[h];

            System.out.printf("══════════ %s   λ=%.5f (%.1f clienti/h) ══════════%n",
                    label, lambda, lambda * 3600.0);

            for (int nCasse = CASSE_MIN; nCasse <= CASSE_MAX; nCasse++) {
                for (int nMag = MAG_MIN; nMag <= MAG_MAX; nMag++) {
                    idx++;

                    Simulation sim = new Simulation();
                    sim.rvms = new Rvms();
                    sim.setArrivalRateInf(lambda);
                    sim.runInfiniteHorizonSimulation(
                            NUM_BATCHES, BATCH_SIZE,
                            INVENTORY_CONFIG,
                            new int[]{nCasse, nMag},
                            false);

                    Simulation.RunResult rr = sim.lastRunResult;
                    double[] sum = sim.getRunSummary(); // [6] = costo inventario (€/giorno operativo)
                    if (centerNamesForInfo == null) centerNamesForInfo = rr.centerNames;

                    Result res      = new Result();
                    res.hourLabel   = label;
                    res.lambda      = lambda;
                    res.numCasse    = nCasse;
                    res.numMag      = nMag;
                    res.sysRT       = rr.sysResponseTime;
                    res.oosProb     = rr.oosProbability;
                    res.abandonProb = rr.abandonProbability; // atteso ~0: abbandoni OFF (§18), colonna diagnostica
                    res.throughput  = rr.throughput;
                    res.utilPerCenter = rr.utilPerCenter;
                    res.utilB1High  = rr.utilBraccioUnoHigh;
                    res.utilB1Low   = rr.utilBraccioUnoLow;
                    res.laborCostDay = (nCasse * COST_CASSA_PER_HOUR + nMag * COST_MAG_PER_HOUR) * OPERATING_HOURS_PER_DAY;
                    res.invCostDay   = sum[6];
                    // Costo d'attesa (€/gg-eq): N clienti/giorno · E[T] in ore · c_w.
                    // Equivalente per Little a c_w·E[N]·12h (E[N] = λ·E[T] = clienti nel sistema).
                    res.waitCostDay  = (lambda * 43200.0) * (res.sysRT / 3600.0) * WAIT_COST_PER_CLIENT_HOUR;
                    res.totalCostDay = res.laborCostDay + res.invCostDay + res.waitCostDay;
                    all.add(res);

                    appendResult(outPath, res);

                    long el = (System.currentTimeMillis() - startMs) / 1000;
                    double eta = (idx > 0) ? (double) el / idx * (total - idx) : 0;
                    System.out.printf(
                            "  [%3d/%3d] casse=%d mag=%d | E[T]=%7.1fs P(OOS)=%.4f X=%.4f/s | "
                          + "C_tot=%8.1f€/gg (lav=%.0f inv=%.1f att=%.1f) | ETA=%ds%n",
                            idx, total, nCasse, nMag, res.sysRT, res.oosProb, res.throughput,
                            res.totalCostDay, res.laborCostDay, res.invCostDay, res.waitCostDay, (long) eta);
                }
            }
        }

        writeRunInfo(hourlyLambdas);
        printBestUnderSla(all, HOUR_LABELS);

        long sec = (System.currentTimeMillis() - startMs) / 1000;
        System.out.printf("%nCompletato in %dm %ds.%n", sec / 60, sec % 60);
        System.out.println("Risultati: " + outPath);
        System.out.println("→ Grafici: python stats/05_infinito_configsearch/plot_configsearch.py");
        System.out.println("  (scomposizione C_tot per config + fronte di Pareto; la scelta sopra usa min C_tot sotto SLA).");
    }

    /**
     * Selezione a fine run con la METRICA ECONOMICA UNICA (tutto in €/gg-equivalente):
     *
     *     C_tot = C_lavoro + C_inventario(holding + shortage + order) + C_attesa
     *     C_attesa = c_w · N_clienti_gg · E[T]_ore     (per Little ≡ c_w · E[N] · 12h)
     *
     * PERCHÉ è la metrica giusta (e non uno score con pesi arbitrari):
     *  - ogni dimensione del trade-off è già monetizzata nel modello di costo del progetto:
     *    OOS → shortage cost dentro C_inventario (§20.3); staffing → tariffe §8; tempo di
     *    risposta → costo d'attesa c_w (unica assunzione nuova, knob -Dcost.wait);
     *  - una config "min OOS ma E[T] enorme" NON può vincere: C_attesa cresce linearmente in
     *    E[T] e la annienta (risolve il difetto del fallback gerarchico min-P(OOS)→min-E[T]);
     *  - il P(OOS) ≤ SLA_OOS resta comunque VINCOLO HARD primario (§3): la metrica sceglie
     *    SOLO tra le config che lo rispettano; se nessuna lo rispetta, fallback = min C_tot
     *    su tutte (lo shortage cost dentro C_tot già spinge verso P(OOS) basso).
     *
     * NON sostituisce il grafico (§21.6): C_tot è la lente con cui leggerlo; la scomposizione
     * lavoro/inventario/attesa è nel grafico a barre di plot_configsearch.py.
     * Costo per fascia = €/gg-equivalente (ranking DENTRO la fascia); il costo REALE del
     * personale sull'intera giornata è Σ_fasce (nCasse·26 + nMag·18)·1h.
     */
    private void printBestUnderSla(List<Result> all, String[] hourLabels) {
        // Raggruppa per fascia preservando l'ordine di hourLabels.
        Map<String, List<Result>> byHour = new LinkedHashMap<>();
        for (String h : hourLabels) byHour.put(h, new ArrayList<>());
        for (Result r : all) byHour.get(r.hourLabel).add(r);

        Comparator<Result> byTotalCost = Comparator.comparingDouble(r -> r.totalCostDay);

        System.out.println("\n" + "=".repeat(100));
        System.out.printf("  CONFIG SCELTA PER FASCIA — min C_tot = lavoro + inventario + attesa (c_w=%.0f €/h·cliente)%n",
                WAIT_COST_PER_CLIENT_HOUR);
        System.out.printf("  feasible = P(OOS) ≤ %.0f%% (SLA hard) E stabile (X ≥ %.0f%%·λ); nessuna feasible → best-effort min C_tot%n",
                SLA_OOS * 100, STABILITY_MIN_X_RATIO * 100);
        System.out.println("=".repeat(100));
        System.out.printf("  %-6s %-8s %-6s %-5s %-9s %-9s %-10s %-9s %-9s %-9s %s%n",
                "Fascia", "λ(cl/h)", "casse", "mag", "E[T](s)", "P(OOS)", "C_tot(€)", "lav(€)", "inv(€)", "att(€)", "note");
        System.out.println("  " + "-".repeat(96));

        int[][] staffing24 = new int[24][2];   // 24 slot da 30' = 12 fasce × 2 (per l'attività 06)
        double totalLaborDay = 0.0;             // costo REALE personale (€/giorno): Σ_fasce (lav €/h)·1h
        boolean allFeasible = true;
        List<String> infeasibleHours = new ArrayList<>();
        StringBuilder tsv = new StringBuilder();

        int hIdx = 0;
        for (Map.Entry<String, List<Result>> e : byHour.entrySet()) {
            String hour = e.getKey();
            List<Result> rows = e.getValue();
            double lambda = rows.isEmpty() ? 0.0 : rows.get(0).lambda;

            // Feasible = SLA hard su P(OOS) + stabilità (X ≥ 90%·λ); tra le feasible → min C_tot
            // (la metrica fa il trade-off E[T]/costo dentro l'insieme ammissibile).
            Result chosen = rows.stream()
                    .filter(r -> r.oosProb <= SLA_OOS
                              && r.throughput >= STABILITY_MIN_X_RATIO * r.lambda)
                    .min(byTotalCost).orElse(null);
            boolean feasible = chosen != null;
            String note = "";
            if (!feasible) {
                // Nessuna feasible: min C_tot su tutte — lo shortage cost già premia OOS basso,
                // il costo d'attesa annienta le instabili. Best-effort marcato.
                allFeasible = false;
                infeasibleHours.add(hour);
                chosen = rows.stream().min(byTotalCost).orElse(null);
                note = "[NO feasible: best-effort min C_tot]";
            }
            if (chosen == null) { hIdx++; continue; }

            System.out.printf("  %-6s %-8.1f %-6d %-5d %-9.1f %-9.4f %-10.1f %-9.0f %-9.1f %-9.1f %s%n",
                    hour, lambda * 3600.0, chosen.numCasse, chosen.numMag,
                    chosen.sysRT, chosen.oosProb, chosen.totalCostDay,
                    chosen.laborCostDay, chosen.invCostDay, chosen.waitCostDay, note);

            staffing24[2 * hIdx]     = new int[]{chosen.numCasse, chosen.numMag};
            staffing24[2 * hIdx + 1] = new int[]{chosen.numCasse, chosen.numMag};
            totalLaborDay += (chosen.numCasse * COST_CASSA_PER_HOUR + chosen.numMag * COST_MAG_PER_HOUR); // ·1h
            tsv.append(hour).append('\t').append(chosen.numCasse).append('\t').append(chosen.numMag)
               .append('\t').append(feasible ? "feasible" : "best-effort").append('\n');
            hIdx++;
        }
        System.out.println("  " + "-".repeat(96));
        System.out.printf("  Costo personale REALE (Σ fasce, lavoro·1h) = %.2f €/giorno%n", totalLaborDay);
        if (!allFeasible)
            System.out.printf("  [ATTENZIONE] Fasce senza config feasible (P(OOS)≤SLA E stabile) — best-effort in tabella: %s%n"
                    + "              → rivedi griglia/inventario o allenta -Dsla.oos.%n",
                    infeasibleHours);

        // Tabella staffing 24-slot pronta da incollare in FiniteHorizonSimulation.Ottimizzato (§ attività 06).
        // Stampata SEMPRE (best-effort dove non feasible): le fasce da rivedere sono elencate sopra.
        System.out.println("\n  Staffing per l'attività 06 (24 slot da 30', incolla in FiniteHorizonSimulation.Ottimizzato):");
        StringBuilder arr = new StringBuilder("  int[][] staffing = {");
        for (int i = 0; i < 24; i++) {
            arr.append("{").append(staffing24[i][0]).append(",").append(staffing24[i][1]).append("}");
            if (i < 23) arr.append(",");
            if ((i + 1) % 6 == 0 && i < 23) arr.append("\n      ");
        }
        arr.append("};");
        System.out.println(arr);
        if (!allFeasible)
            System.out.println("  (⚠ contiene fasce best-effort: vedi elenco sopra prima di usarla)");

        // best_staffing.tsv: consumabile da plot_configsearch.py --staffing per marcare la scelta.
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outDir + "/best_staffing.tsv", false))) {
            bw.write("# hour\tnCasse\tnMag\tstatus  (min C_tot=lavoro+inventario+attesa, c_w="
                    + WAIT_COST_PER_CLIENT_HOUR + " EUR/h; feasible = P(OOS)<=" + SLA_OOS
                    + " E stabile X>=" + STABILITY_MIN_X_RATIO + "*lambda; best-effort = min C_tot se nessuna feasible)\n");
            bw.write(tsv.toString());
        } catch (IOException ex) {
            System.err.println("Errore best_staffing.tsv: " + ex.getMessage());
        }
    }

    private void appendResult(String path, Result r) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path, true))) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%s\t%.5f\t%d\t%d\t%.3f\t%.5f\t%.5f\t%.5f\t%.2f\t%.2f\t%.2f\t%.2f",
                    r.hourLabel, r.lambda, r.numCasse, r.numMag,
                    r.sysRT, r.oosProb, r.abandonProb, r.throughput,
                    r.laborCostDay, r.invCostDay, r.waitCostDay, r.totalCostDay));
            if (r.utilPerCenter != null) {
                for (double u : r.utilPerCenter) sb.append(String.format(Locale.US, "\t%.4f", u));
            }
            sb.append(String.format(Locale.US, "\t%.4f\t%.4f", r.utilB1High, r.utilB1Low));
            bw.write(sb.toString());
            bw.newLine();
        } catch (IOException e) {
            System.err.println("Errore append: " + e.getMessage());
        }
    }

    private void writeRunInfo(double[] hourlyLambdas) {
        new File(outDir).mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outDir + "/_run_info.txt"))) {
            w.write("scenario      = " + scenarioLabel); w.newLine();
            w.write("timestamp     = " + java.time.LocalDateTime.now()); w.newLine();
            w.write("num_batches   = " + NUM_BATCHES); w.newLine();
            w.write("batch_size    = " + BATCH_SIZE); w.newLine();
            w.write("inventory_s   = " + Arrays.toString(INVENTORY_CONFIG) + "  (fissa, da attivita' 04, CLAUDE.md §21.1)"); w.newLine();
            w.write("staffing_grid = casse[" + CASSE_MIN + ".." + CASSE_MAX + "] x mag[" + MAG_MIN + ".." + MAG_MAX + "]"); w.newLine();
            w.write("cost_rates    = cassa " + COST_CASSA_PER_HOUR + " EUR/h, magazziniere " + COST_MAG_PER_HOUR + " EUR/h (CLAUDE.md §8)"); w.newLine();
            w.write("cost_wait     = " + WAIT_COST_PER_CLIENT_HOUR + " EUR/h·cliente  (C_attesa = c_w·N_clienti·E[T]; knob -Dcost.wait)"); w.newLine();
            w.write("sla_oos       = " + SLA_OOS + "  (vincolo hard primario; knob -Dsla.oos)"); w.newLine();
            w.write("hourly_lambda = " + Arrays.toString(hourlyLambdas)); w.newLine();
            if (centerNamesForInfo != null) {
                w.write("center_order  = " + Arrays.toString(centerNamesForInfo)
                        + "  (ordine delle colonne util_* in results.dat)"); w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Errore _run_info.txt: " + e.getMessage());
        }
    }
}
