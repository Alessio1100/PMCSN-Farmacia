package it.farmacia.control;



import it.farmacia.model.Statistics;
import it.farmacia.model.StreamType;
import it.farmacia.utils.*;

import java.awt.image.SampleModel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

public class FiniteHorizonSimulation {

    private final int NUM_REPLICATIONS = Integer.getInteger("reps", 128);
    private final int NUM_SAMPLING = 96;
    // Cartella di output dei .dat: override con -Dstats.dir=stats/NN_attivita/dat per far atterrare
    // gli output nell'attività corrente del piano esperimenti. Default = vecchia cartella piatta.
    Simulation[] simulations = new Simulation[NUM_REPLICATIONS];

    // ── Parametri dello SCENARIO (impostati dai PUNTI D'INGRESSO qui sotto) ──────────
    // Cartella di output: default -Dstats.dir; ogni scenario la sovrascrive con la sua cartella
    // del piano esperimenti → impossibile scrivere una config nella cartella di un'altra.
    private String  outDir              = System.getProperty("stats.dir", "stats/finite");
    // Soglie di riordino s per classe (le S sono fisse in Simulation).
    private int[]   inventory_config    = {75, 65, 55, 40, 30};
    // f_init: frazione di S a cui parte il banco (1.0 = banco pieno).
    private double  init_level_fraction = 1.0;
    // Periodo di revisione (R) della politica (R,s,S), in secondi. La BASELINE ("gestione attuale")
    // usa R=3600 (60 min): revisione poco reattiva, tipica di una gestione non ottimizzata → OOS
    // più alto. Gli scenari OTTIMIZZATI usano R=1800 (30 min): la revisione più frequente è UNA
    // DELLE OTTIMIZZAZIONI individuate dallo studio (abbassa gli OOS). Deve essere multiplo di 1800.
    private int     review_seconds      = 1800;
    // Staffing per fascia {#casse, #magazzinieri} (24 fasce). Default = pieno.
    private int[][] staffing            = STAFFING_PIENO;
    // Etichetta dello scenario (stampata e scritta in _run_info.txt).
    private String  scenarioLabel       = "default";

    // Staffing "gestione attuale": 5 casse + 2 magazzinieri in tutte le 24 fasce.
    static final int[][] STAFFING_PIENO = {
            {5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},
            {5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2},{5,2}
    };

    // Tariffe di lavoro (CLAUDE.md §8), per il costo del personale in summary.dat.
    private static final double COST_CASSA_PER_HOUR = 26.0;   // € / farmacista / ora
    private static final double COST_MAG_PER_HOUR   = 18.0;   // € / magazziniere / ora

    private static String prop(String key, String def) { return System.getProperty(key, def); }

    /** Staffing per fascia dallo staffing OTTIMO della search 05 (best_staffing.tsv):
     *  righe "HH-HH\tnCasse\tnMag[\tstatus]" → espanse nei 24 slot da 30'. */
    static int[][] staffingFromBestTsv(String path) {
        int[][] st = new int[24][];
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                int hour = Integer.parseInt(p[0].split("-")[0]);   // "08-09" → 8
                int idx  = (hour - 8) * 2;
                int[] cm = {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
                if (idx >= 0 && idx + 1 < 24) { st[idx] = cm; st[idx + 1] = cm; }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile leggere lo staffing ottimo da " + path
                    + " — esegui prima ConfigurationSearch (attivita' 05). Causa: " + e.getMessage(), e);
        }
        for (int i = 0; i < 24; i++)
            if (st[i] == null)
                throw new IllegalStateException("Staffing mancante per lo slot " + i + " in " + path);
        return st;
    }

    /** Staffing per fascia = COPERTURA dei turni REALISTICI generati da turni_cassieri.py
     *  (turni_generati_search.dat, formato META: "META nome start end colore ruolo"). */
    static int[][] staffingFromTurniDat(String path) {
        java.util.List<int[]> workers = new java.util.ArrayList<>(); // {start, end, ruolo: 0=cassiere 1=magazziniere}
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("META")) continue;
                String[] p = line.split("\\s+");
                workers.add(new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        p[5].startsWith("cassiere") ? 0 : 1});
            }
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile leggere i turni da " + path
                    + " — esegui prima turni_cassieri.py (genera i turni dalla search 05). Causa: " + e.getMessage(), e);
        }
        if (workers.isEmpty())
            throw new IllegalStateException("Nessuna riga META in " + path);
        int[][] st = new int[24][2];
        for (int slot = 0; slot < 24; slot++) {
            double hour = 8.0 + slot * 0.5;
            for (int[] w : workers)
                if (w[0] <= hour && hour < w[1]) st[slot][w[2]]++;
        }
        return st;
    }

    // ═══════════════ PUNTI D'INGRESSO (uno per caso del piano esperimenti) ═══════════════
    // In IntelliJ compaiono come target eseguibili separati (FiniteHorizonSimulation.Baseline,
    // FiniteHorizonSimulation.Ottimizzato): crea una run configuration per ognuno col nome del caso.
    // Ogni entry point imposta i PROPRI dati e la PROPRIA cartella → nessuna ambiguità.

    /** Caso 03 — BASELINE finito: staffing pieno + inventario PRE-ottimizzazione (guardrail §19). */
    public static class Baseline {
        public static void main(String[] args) {
            FiniteHorizonSimulation s = new FiniteHorizonSimulation();
            s.scenarioLabel       = "03_BASELINE_FINITO";
            s.inventory_config    = new int[]{60, 55, 45, 45, 25};
            s.init_level_fraction = 1.0;
            s.review_seconds      = 3600;   // gestione attuale: revisione ogni 60 min (non ottimizzata)
            s.staffing            = STAFFING_PIENO;
            s.outDir              = prop("stats.dir", "stats/03_baseline_finito/dat");
            s.run();
        }
    }

    /** Caso 06 — OTTIMIZZATO finito: inventario scelto {75,65,55,40,30} + staffing OTTIMO per
     *  fascia letto da best_staffing.tsv (output della search 05, si aggiorna da solo quando si
     *  rilancia la search; override con -Dstaffing.tsv). */
    public static class Ottimizzato {
        public static void main(String[] args) {
            FiniteHorizonSimulation s = new FiniteHorizonSimulation();
            s.scenarioLabel       = "06_OTTIMIZZATO_FINITO";
            s.inventory_config    = new int[]{75, 65, 55, 40, 30};
            s.init_level_fraction = 1.0;
            s.staffing            = staffingFromBestTsv(
                    prop("staffing.tsv", "stats/05_infinito_configsearch/dat/best_staffing.tsv"));
            s.outDir              = prop("stats.dir", "stats/06_ottimizzato_finito/dat");
            s.run();
        }
    }

    /** Caso 07 — OTTIMIZZATO + TURNI REALISTICI: come il 06 ma con lo staffing = COPERTURA dei
     *  turni contigui generati da turni_cassieri.py (turni_generati_search.dat; override con
     *  -Dturni.dat). Copertura ≥ staffing ottimo per costruzione → stesso SLA, costo personale
     *  leggermente più alto ("costo del realismo"). */
    public static class Ottimizzato_Turni {
        public static void main(String[] args) {
            FiniteHorizonSimulation s = new FiniteHorizonSimulation();
            s.scenarioLabel       = "07_OTTIMIZZATO_TURNI_FINITO";
            s.inventory_config    = new int[]{75, 65, 55, 40, 30};
            s.init_level_fraction = 1.0;
            s.staffing            = staffingFromTurniDat(
                    prop("turni.dat", "turni_generati_search.dat"));
            s.outDir              = prop("stats.dir", "stats/07_ottimizzato_turni_finito/dat");
            s.run();
        }
    }

    /** Entry point legacy: esegue il caso OTTIMIZZATO (compatibilità coi vecchi lanci). */
    public static void main(String[] args) {
        Ottimizzato.main(args);
    }

    private void run() {

        // Configurazione dei centri (numero server per ogni centro) nelle diverse fasce orarie
        // Ogni riga corrisponde a una fascia oraria, l'i-esima colonna corrisponde al numero di server per l'i-esimo centro
//        int[][] configCenters = new int[][] {
//                {2, 1},
//                {2, 1},
//                {4, 1},
//                {4, 1},
//                {4, 2},
//                {4, 2},
//                {5, 2},
//                {5, 2},
//                {5, 2},
//                {5, 2},
//                {3, 1},
//                {3, 1},
//                {4, 1},
//                {4, 1},
//                {3, 1},
//                {3, 1},
//                {5, 2},
//                {5, 2},
//                {5, 2},
//                {5, 2},
//                {4, 2},
//                {4, 2},
//                {5, 2},
//                {5, 2}
//        };

        // Staffing dello scenario corrente (impostato dal punto d'ingresso). NON più hardcoded qui.
        int[][] configCenters = staffing;
        System.out.printf("=== SCENARIO: %s | s=%s f_init=%.2f | out=%s ===%n",
                scenarioLabel, java.util.Arrays.toString(inventory_config), init_level_fraction, outDir);

        // Tassi di arrivo di ognuna delle fasce orarie (+20% rispetto ai valori originali)
        double[] slotRates = new double[] {
                0.007050,      // slot  0 (08:00-08:30)
                0.008615000,   // slot  1 (08:30-09:00)
                0.015500000,   // slot  2 (09:00-09:30)
                0.018335000,   // slot  3 (09:30-10:00)
                0.019800,      // slot  4 (10:00-10:30)
                0.016200,      // slot  5 (10:30-11:00)
                0.022200,      // slot  6 (11:00-11:30)
                0.027135000,   // slot  7 (11:30-12:00)
                0.024935000,   // slot  8 (12:00-12:30)
                0.020400,      // slot  9 (12:30-13:00)
                0.012900,      // slot 10 (13:00-13:30)
                0.015765000,   // slot 11 (13:30-14:00)
                0.016135000,   // slot 12 (14:00-14:30)
                0.013200,      // slot 13 (14:30-15:00)
                0.012000,      // slot 14 (15:00-15:30)
                0.014665000,   // slot 15 (15:30-16:00)
                0.017700,      // slot 16 (16:00-16:30)
                0.021635000,   // slot 17 (16:30-17:00)
                0.024000,      // slot 18 (17:00-17:30)
                0.029335000,   // slot 19 (17:30-18:00)
                0.030435000,   // slot 20 (18:00-18:30)
                0.024900,      // slot 21 (18:30-19:00)
                0.025500,      // slot 22 (19:00-19:30)
                0.020000000    // slot 23 (19:30-20:00)
        };
        // NOTA: questi slotRates NON sono la sorgente effettiva degli arrivi: la generazione usa
        // il campo Simulation.arrivalRates (valori RAW della relazione, media giornaliera = 0.0190975,
        // coerente con il λ dell'orizzonte infinito). Restano qui solo per compatibilità con
        // setArrivalRate (non usato per la generazione).

        // Gestione RNG per garantire l'indipendenza e la continuità degli stream tra le repliche
        Rngs r = new Rngs();
        r.plantSeeds(123456789);
        Rvgs v = new Rvgs(r);

        // ⚠️ MODIFICA MEMORY LEAK: Usiamo un array di matrici di statistiche invece di array di Simulation
        // Questo permette al Garbage Collector di liberare la memoria della simulazione appena conclusa.
        Statistics[][][] allMatrices = new Statistics[NUM_REPLICATIONS][][];
        Simulation lastSimulation = null;

        // Accumulatori dei totali per-replica (per i riepiloghi medi: abbandoni, OOS, ecc.)
        double[] sumSummary = new double[8];

        // Esegue NUM_REPLICATIONS repliche
        for (int i = 0; i < NUM_REPLICATIONS; i++) {
            System.out.println("Replica n. " + (i+1));

            Simulation simulation = new Simulation();
            simulation.setNumSampling(NUM_SAMPLING);
            simulation.rvms = new Rvms();

            // Passaggio dei generatori: gli stati finali diventano quelli iniziali della prossima replica
            simulation.initSeed(r, v);

            r.selectStream(StreamType.STREAM_ARRIVAL);
            double testValue = r.random();
            System.out.println("Replica " + (i+1) + " - Primo valore random: " + testValue);

            // Imposta lo stop a 12 ore (43200 secondi)
            simulation.setStop(43200);
            // Livello iniziale = f_init·S (banco pieno con 1.0), coerente con la config scelta.
            simulation.setInitLevelFraction(init_level_fraction);
            // Periodo di revisione (R) dello scenario: 60 min baseline, 30 min ottimizzati.
            simulation.setReviewSeconds(review_seconds);

            if (i == 1) {
                System.out.println("ciao"); // Debug message mantenuto dall'originale
            }

            // Esecuzione della singola replica
            simulation.runFiniteHorizonSimulation(configCenters, slotRates, inventory_config);

            // Accumula i totali per-replica per il riepilogo medio
            double[] rep = simulation.getRunSummary();
            for (int k = 0; k < sumSummary.length; k++) sumSummary[k] += rep[k];

            // ⚠️ SALVATAGGIO OTTIMIZZATO: Salviamo solo i risultati (matrice leggera), non l'intera simulazione
            allMatrices[i] = simulation.matrix;

            // Conserva l'ultima replica per scrivere i dati inventario
            if (i == NUM_REPLICATIONS - 1) {
                lastSimulation = simulation;
            }
        }

        // --- RIEPILOGO MEDIO PER REPLICA (abbandoni, OOS, ecc.) ---
        double nrep = NUM_REPLICATIONS;
        double avgGen     = sumSummary[5] / nrep;
        double avgVend    = sumSummary[3] / nrep;
        double avgCar     = sumSummary[4] / nrep;
        double avgAbb     = sumSummary[0] / nrep;
        double avgOos     = sumSummary[1] / nrep;
        double avgExits   = sumSummary[2] / nrep;
        double avgCost    = sumSummary[6] / nrep;
        double avgLagMin  = (sumSummary[7] / nrep) / 60.0;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("   RIEPILOGO MEDIO PER REPLICA (su " + NUM_REPLICATIONS + " repliche)");
        System.out.println("=".repeat(60));
        System.out.printf("  Clienti serviti (medi) ............. = %.1f%n", avgExits);
        System.out.printf("  Abbandoni (medi) ................... = %.1f%n", avgAbb);
        System.out.printf("  Farmaci richiesti (medi) ........... = %.1f%n", avgGen);
        System.out.printf("  Farmaci venduti (medi) ............. = %.1f%n", avgVend);
        System.out.printf("  Farmaci caricati (medi) ............ = %.1f%n", avgCar);
        System.out.printf("  Out-of-stock articoli (medi) ....... = %.1f%n", avgOos);
        System.out.printf("  P(out-of-stock) articoli ........... = %.3f%n", avgGen > 0 ? avgOos / avgGen : 0.0);
        System.out.printf("  P(abbandono) clienti ............... = %.3f%n", (avgExits + avgAbb) > 0 ? avgAbb / (avgExits + avgAbb) : 0.0);
        System.out.printf("  Costo inventario medio (EUR) ....... = %.2f%n", avgCost);
        System.out.printf("  Delivery lag medio (min) ........... = %.1f%n", avgLagMin);
        double laborCost = laborCostPerDay();
        System.out.printf("  Costo personale (EUR/giorno) ....... = %.2f%n", laborCost);
        System.out.printf("  COSTO TOTALE (EUR/giorno) .......... = %.2f%n", laborCost + avgCost);

        // Scrivi la time series inventario (livello + OOS cumulati per estrazione) dell'ultima replica
        new File(outDir).mkdirs();
        if (lastSimulation != null) {
            lastSimulation.writeInventoryLevelSeries(outDir);
        }
        writeRunInfo();  // provenienza: quale scenario/parametri hanno prodotto questa cartella
        // summary.dat machine-readable: KPI aggregati + costi, per il confronto a 3 scenari
        // (stats/08_confronto_finito/confronto_scenari.py legge questi file dai 3 dat/).
        writeSummaryDat(avgExits, avgAbb, avgGen, avgVend, avgCar, avgOos, avgCost, avgLagMin, laborCost);

        // --- AGGREGAZIONE DATI ---
        // Costruisco la matrice che contiene le statistiche mediate su tutte le repliche
        Statistics[][] matrix = new Statistics[7][NUM_SAMPLING];

        // Per ogni centro (7 centri totali: Casse, Online, Dispatcher, B1, B2, Magazziniere, Pagamento)
        // --- AGGREGAZIONE DATI ---
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < NUM_SAMPLING; j++) {
                Statistics currentStat = new Statistics(1);

                for (int k = 0; k < NUM_REPLICATIONS; k++) {
                    currentStat.avgWait[0] += allMatrices[k][i][j].avgWait[0];
                    currentStat.avgNode[0] += allMatrices[k][i][j].avgNode[0];

                    // RIMOSSA LA CONDIZIONE if (i != 5)
                    currentStat.avgDelay[0] += allMatrices[k][i][j].avgDelay[0];
                    currentStat.avgInterarrivals[0] += allMatrices[k][i][j].avgInterarrivals[0];
                    currentStat.avgQueue[0] += allMatrices[k][i][j].avgQueue[0];
                    currentStat.utilization[0] += allMatrices[k][i][j].utilization[0];
                    currentStat.avgService[0] += allMatrices[k][i][j].avgService[0];

                    currentStat.totalSuccessfulExits = allMatrices[k][i][j].totalSuccessfulExits;
                    currentStat.totalReneging = allMatrices[k][i][j].totalReneging;
                    currentStat.totalOutOfStock = allMatrices[k][i][j].totalOutOfStock;
                    currentStat.totalGeneratedJob = allMatrices[k][i][j].totalGeneratedJob;

                    currentStat.cassaCompletions += allMatrices[k][i][j].cassaCompletions;
                    currentStat.cassaOnlineCompletions += allMatrices[k][i][j].cassaOnlineCompletions;
                    currentStat.dispatcherCompletions += allMatrices[k][i][j].dispatcherCompletions;
                    currentStat.lowPriorityCompletions += allMatrices[k][i][j].lowPriorityCompletions;
                    currentStat.braccioDueCompletions += allMatrices[k][i][j].braccioDueCompletions;

                    if (i == 3) {
                        currentStat.lossProbability += allMatrices[k][i][j].lossProbability;
                    }
                }

                // Calcolo della media finale
                currentStat.avgWait[0] /= NUM_REPLICATIONS;
                currentStat.avgNode[0] /= NUM_REPLICATIONS;
                currentStat.avgDelay[0] /= NUM_REPLICATIONS;
                currentStat.avgInterarrivals[0] /= NUM_REPLICATIONS;
                currentStat.avgQueue[0] /= NUM_REPLICATIONS;
                currentStat.utilization[0] /= NUM_REPLICATIONS;
                currentStat.avgService[0] /= NUM_REPLICATIONS;

                currentStat.totalSuccessfulExits /= NUM_REPLICATIONS;
                currentStat.totalReneging /= NUM_REPLICATIONS;
                currentStat.totalOutOfStock /= NUM_REPLICATIONS;
                currentStat.totalGeneratedJob /= NUM_REPLICATIONS;

                currentStat.cassaCompletions /= NUM_REPLICATIONS;
                currentStat.cassaOnlineCompletions /= NUM_REPLICATIONS;
                currentStat.dispatcherCompletions /= NUM_REPLICATIONS;
                currentStat.lowPriorityCompletions /= NUM_REPLICATIONS;
                currentStat.braccioDueCompletions /= NUM_REPLICATIONS;

                if (i == 3) {
                    currentStat.lossProbability /= NUM_REPLICATIONS;
                }

                matrix[i][j] = currentStat;
            }
        }

        // Generazione dei file .dat e stampa degli intervalli di confidenza
        generateSamplingEstimate(matrix, allMatrices, NUM_SAMPLING);
    }

    private void generateSamplingEstimate(Statistics[][] matrix, Statistics[][][] allMatrices, int numSampling) {
        String[] centerNames = {"Casse Farmacia", "Cassa Online", "Dispatcher", "Braccio Uno", "Braccio Due", "Magazziniere", "Casse Pagamento"};
        for (int i = 0; i < 7; i++) {
            double[] avgInterrarivals = new double[numSampling];
            double[] avgWait          = new double[numSampling];
            double[] avgDelay         = new double[numSampling];
            double[] avgNode          = new double[numSampling];
            double[] avgQueue         = new double[numSampling];
            double[] utilization      = new double[numSampling];
            double[] avgService       = new double[numSampling];
            double[] lossProbabilities = new double[numSampling];

            // Per-replica arrays per CI
            double[][] repAvgWait          = new double[numSampling][NUM_REPLICATIONS];
            double[][] repAvgNode          = new double[numSampling][NUM_REPLICATIONS];
            double[][] repAvgQueue         = new double[numSampling][NUM_REPLICATIONS];
            double[][] repUtil             = new double[numSampling][NUM_REPLICATIONS];
            double[][] repAvgDelay         = new double[numSampling][NUM_REPLICATIONS];
            double[][] repLossProb         = new double[numSampling][NUM_REPLICATIONS];
            double[][] repAvgInterarrivals = new double[numSampling][NUM_REPLICATIONS];

            for (int j = 0; j < numSampling; j++) {
                Statistics agg = matrix[i][j];
                avgInterrarivals[j] = agg.avgInterarrivals[0];
                avgWait[j]          = agg.avgWait[0];
                avgDelay[j]         = agg.avgDelay[0];
                avgNode[j]          = agg.avgNode[0];
                avgQueue[j]         = agg.avgQueue[0];
                utilization[j]      = agg.utilization[0];
                avgService[j]       = agg.avgService[0];
                if (i == 3) lossProbabilities[j] = agg.lossProbability;

                for (int k = 0; k < NUM_REPLICATIONS; k++) {
                    Statistics r = allMatrices[k][i][j];
                    repAvgWait[j][k]          = r.avgWait[0];
                    repAvgNode[j][k]          = r.avgNode[0];
                    repAvgQueue[j][k]         = r.avgQueue[0];
                    repUtil[j][k]             = r.utilization[0];
                    repAvgDelay[j][k]         = r.avgDelay[0];
                    repAvgInterarrivals[j][k] = r.avgInterarrivals[0];
                    if (i == 3) repLossProb[j][k] = r.lossProbability;
                }
            }

            System.out.println("=========== For " + centerNames[i] + ": ===================");
            System.out.print("\nInterrarivals: ...... = ");
            Estimate.estimate(avgInterrarivals);
            generateDatFileCI(repAvgWait,          centerNames[i], "Response");
            generateDatFileCI(repAvgDelay,         centerNames[i], "Wait");
            generateDatFileCI(repAvgNode,          centerNames[i], "Node");
            generateDatFileCI(repAvgQueue,         centerNames[i], "Queue");
            generateDatFileCI(repUtil,             centerNames[i], "Utilization");
            generateDatFileCI(repAvgInterarrivals, centerNames[i], "avgInterarrivals");
            System.out.print("Response Time: ...... = ");
            Estimate.estimate(avgWait);
            System.out.print("Waiting Time (coda):  = ");
            Estimate.estimate(avgDelay);
            System.out.print("Avg Jobs in Node: ... = ");
            Estimate.estimate(avgNode);
            System.out.print("Avg Jobs in Queue: .. = ");
            Estimate.estimate(avgQueue);
            System.out.print("Avg Service Time: ... = ");
            Estimate.estimate(avgService);
            System.out.print("Utilization: ........ = ");
            Estimate.estimate(utilization);
            if (i == 3) {
                generateDatFileCI(repLossProb, centerNames[i], "lossProbability");
                System.out.print("Loss Probability: ");
                Estimate.estimate(lossProbabilities);

                // ── .dat PER-CODA del Braccio Uno: HIGH = carico/rifornimento, LOW = prelievi cliente ──
                double[][] hiWait = new double[numSampling][NUM_REPLICATIONS], loWait = new double[numSampling][NUM_REPLICATIONS];
                double[][] hiDelay= new double[numSampling][NUM_REPLICATIONS], loDelay= new double[numSampling][NUM_REPLICATIONS];
                double[][] hiNode = new double[numSampling][NUM_REPLICATIONS], loNode = new double[numSampling][NUM_REPLICATIONS];
                double[][] hiQueue= new double[numSampling][NUM_REPLICATIONS], loQueue= new double[numSampling][NUM_REPLICATIONS];
                double[][] hiUtil = new double[numSampling][NUM_REPLICATIONS], loUtil = new double[numSampling][NUM_REPLICATIONS];
                for (int j = 0; j < numSampling; j++) {
                    for (int k = 0; k < NUM_REPLICATIONS; k++) {
                        Statistics r = allMatrices[k][i][j];
                        hiWait[j][k]=r.avgWaitHigh;  loWait[j][k]=r.avgWaitLow;
                        hiDelay[j][k]=r.avgDelayHigh; loDelay[j][k]=r.avgDelayLow;
                        hiNode[j][k]=r.avgNodeHigh;  loNode[j][k]=r.avgNodeLow;
                        hiQueue[j][k]=r.avgQueueHigh; loQueue[j][k]=r.avgQueueLow;
                        hiUtil[j][k]=r.utilHigh;     loUtil[j][k]=r.utilLow;
                    }
                }
                generateDatFileCI(hiWait, "Braccio Uno HIGH", "Response");
                generateDatFileCI(hiDelay,"Braccio Uno HIGH", "Wait");
                generateDatFileCI(hiNode, "Braccio Uno HIGH", "Node");
                generateDatFileCI(hiQueue,"Braccio Uno HIGH", "Queue");
                generateDatFileCI(hiUtil, "Braccio Uno HIGH", "Utilization");
                generateDatFileCI(loWait, "Braccio Uno LOW",  "Response");
                generateDatFileCI(loDelay,"Braccio Uno LOW",  "Wait");
                generateDatFileCI(loNode, "Braccio Uno LOW",  "Node");
                generateDatFileCI(loQueue,"Braccio Uno LOW",  "Queue");
                generateDatFileCI(loUtil, "Braccio Uno LOW",  "Utilization");
                System.out.println("  (scritti .dat per-coda: 'Braccio Uno HIGH'=carico, 'Braccio Uno LOW'=prelievi)");
            }
            System.out.println("");
        }

        // --- STATISTICHE GLOBALI DEL SISTEMA ---
        double[] sysAvgWait = new double[numSampling];
        double[] sysAvgNode = new double[numSampling];
        double[][] repSysWait = new double[numSampling][NUM_REPLICATIONS];
        double[][] repSysNode = new double[numSampling][NUM_REPLICATIONS];

        for (int j = 0; j < numSampling; j++) {
            double n0 = matrix[0][j].cassaCompletions;
            double n1 = matrix[1][j].cassaOnlineCompletions;
            double w1 = (n0 + n1 > 0) ?
                    (matrix[0][j].avgWait[0] * n0 + matrix[1][j].avgWait[0] * n1) / (n0 + n1) : 0;
            double w2 = matrix[2][j].avgWait[0];
            double f3 = matrix[3][j].lowPriorityCompletions;
            double f4 = matrix[4][j].braccioDueCompletions;
            double w3 = (f3 + f4 > 0) ?
                    (matrix[3][j].avgWait[0] * f3 + matrix[4][j].avgWait[0] * f4) / (f3 + f4) : 0;
            double w4 = matrix[6][j].avgWait[0]; // CassePagamento (clienti fisici)
            sysAvgWait[j] = w1 + w2 + w3 + w4;
            sysAvgNode[j] = matrix[0][j].avgNode[0] + matrix[1][j].avgNode[0] +
                    matrix[2][j].avgNode[0] + matrix[3][j].avgNode[0] + matrix[4][j].avgNode[0];

            for (int k = 0; k < NUM_REPLICATIONS; k++) {
                double rn0 = allMatrices[k][0][j].cassaCompletions;
                double rn1 = allMatrices[k][1][j].cassaOnlineCompletions;
                double rw1 = (rn0 + rn1 > 0) ?
                        (allMatrices[k][0][j].avgWait[0] * rn0 + allMatrices[k][1][j].avgWait[0] * rn1) / (rn0 + rn1) : 0;
                double rw2 = allMatrices[k][2][j].avgWait[0];
                double rf3 = allMatrices[k][3][j].lowPriorityCompletions;
                double rf4 = allMatrices[k][4][j].braccioDueCompletions;
                double rw3 = (rf3 + rf4 > 0) ?
                        (allMatrices[k][3][j].avgWait[0] * rf3 + allMatrices[k][4][j].avgWait[0] * rf4) / (rf3 + rf4) : 0;
                repSysWait[j][k] = rw1 + rw2 + rw3;
                repSysNode[j][k] = allMatrices[k][0][j].avgNode[0] + allMatrices[k][1][j].avgNode[0] +
                        allMatrices[k][2][j].avgNode[0] + allMatrices[k][3][j].avgNode[0] + allMatrices[k][4][j].avgNode[0];
            }
        }

        System.out.println("For overall system:");
        System.out.println("System Response Time:");
        Estimate.estimate(sysAvgWait);
        generateDatFileCI(repSysWait, "system", "Response");
        System.out.println("Avg Jobs in System:");
        Estimate.estimate(sysAvgNode);
        generateDatFileCI(repSysNode, "system", "Node");

        // ── METRICHE PER GIUSTIFICARE LE PRIME FASCE ORARIE ─────────────────────────
        // arrivalsPerWindow  : clienti arrivati (Casse+Online) in ogni finestra di 450s
        // completionsPerWindow: completamenti totali Casse+Online per finestra
        // abandonPerWindow   : abbandoni per finestra
        // Queste metriche mostrano chiaramente che nelle prime 8 finestre (prima ora,
        // λ=0.007-0.009/s) arrivano ~3-4 clienti ogni 450s vs. 12-14 al picco →
        // spiegazione naturale dei bassi E[T], E[N] e completamenti nelle prime fasce.
        double[][] repArrivals     = new double[numSampling][NUM_REPLICATIONS];
        double[][] repCompletions  = new double[numSampling][NUM_REPLICATIONS];
        double[][] repAbandon      = new double[numSampling][NUM_REPLICATIONS];

        for (int j = 0; j < numSampling; j++) {
            for (int k = 0; k < NUM_REPLICATIONS; k++) {
                // totalGeneratedJob è uguale per tutti i centri nella stessa finestra (centro 0 = proxy)
                repArrivals[j][k]    = allMatrices[k][0][j].totalGeneratedJob;
                repCompletions[j][k] = allMatrices[k][0][j].cassaCompletions
                                     + allMatrices[k][1][j].cassaOnlineCompletions;
                repAbandon[j][k]     = allMatrices[k][0][j].totalReneging;
            }
        }

        generateDatFileCI(repArrivals,    "system", "arrivalsPerWindow");
        generateDatFileCI(repCompletions, "system", "completionsPerWindow");
        generateDatFileCI(repAbandon,     "system", "abandonPerWindow");

        System.out.println("Arrivals per sampling window (450s):");
        double[] meanArrivals = new double[numSampling];
        for (int j = 0; j < numSampling; j++) {
            double sum = 0;
            for (int k = 0; k < NUM_REPLICATIONS; k++) sum += repArrivals[j][k];
            meanArrivals[j] = sum / NUM_REPLICATIONS;
        }
        Estimate.estimate(meanArrivals);
    }

    /**
     * Scrive un dat con 3 colonne: mean, lower_ci, upper_ci (IC 95%, t-distribution).
     * perSlotPerReplica[j][k] = valore della metrica al campionamento j per la replica k.
     */
    /** Costo del personale per giornata operativa (€/gg): Σ sui 24 slot da 30' di
     *  (nCasse·26 + nMag·18)·0.5h — tariffe §8, dipende SOLO dallo staffing dello scenario. */
    private double laborCostPerDay() {
        double tot = 0.0;
        for (int[] slot : staffing)
            tot += (slot[0] * COST_CASSA_PER_HOUR + slot[1] * COST_MAG_PER_HOUR) * 0.5;
        return tot;
    }

    /** Scrive _run_info.txt nella cartella di output: rende ogni cartella auto-documentata su QUALE
     *  scenario/parametri l'hanno prodotta → niente confusione tra baseline e ottimizzato. */
    private void writeRunInfo() {
        new File(outDir).mkdirs();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outDir + "/_run_info.txt"))) {
            w.write("scenario   = " + scenarioLabel); w.newLine();
            w.write("timestamp  = " + java.time.LocalDateTime.now()); w.newLine();
            w.write("reps       = " + NUM_REPLICATIONS); w.newLine();
            w.write("inventory_s= " + java.util.Arrays.toString(inventory_config)); w.newLine();
            w.write("f_init     = " + init_level_fraction); w.newLine();
            w.write("staffing   = " + java.util.Arrays.deepToString(staffing)
                    + "  (24 slot da 30'; {#casse,#magazzinieri})"); w.newLine();
            w.write("labor_cost = " + String.format(Locale.US, "%.2f", laborCostPerDay())
                    + " EUR/giorno  (26/18 EUR/h, CLAUDE.md §8)"); w.newLine();
        } catch (IOException e) {
            System.err.println("Errore scrittura _run_info.txt: " + e.getMessage());
        }
    }

    /** summary.dat: KPI aggregati e costi in formato chiave\tvalore, per il confronto a 3 scenari
     *  (baseline / ottimizzato / ottimizzato+turni) fatto da confronto_scenari.py. */
    private void writeSummaryDat(double avgExits, double avgAbb, double avgGen, double avgVend,
                                 double avgCar, double avgOos, double avgInvCost, double avgLagMin,
                                 double laborCost) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outDir + "/summary.dat"))) {
            w.write("# chiave\tvalore   (medie per replica su " + NUM_REPLICATIONS + " repliche; costi in EUR/giorno)");
            w.newLine();
            w.write("scenario\t" + scenarioLabel); w.newLine();
            w.write("reps\t" + NUM_REPLICATIONS); w.newLine();
            w.write(String.format(Locale.US, "clienti_serviti\t%.2f%n", avgExits));
            w.write(String.format(Locale.US, "abbandoni\t%.2f%n", avgAbb));
            w.write(String.format(Locale.US, "farmaci_richiesti\t%.2f%n", avgGen));
            w.write(String.format(Locale.US, "farmaci_venduti\t%.2f%n", avgVend));
            w.write(String.format(Locale.US, "farmaci_caricati\t%.2f%n", avgCar));
            w.write(String.format(Locale.US, "oos_articoli\t%.2f%n", avgOos));
            w.write(String.format(Locale.US, "p_oos\t%.5f%n", avgGen > 0 ? avgOos / avgGen : 0.0));
            w.write(String.format(Locale.US, "p_abbandono\t%.5f%n",
                    (avgExits + avgAbb) > 0 ? avgAbb / (avgExits + avgAbb) : 0.0));
            w.write(String.format(Locale.US, "delivery_lag_min\t%.2f%n", avgLagMin));
            w.write(String.format(Locale.US, "costo_inventario_eur_gg\t%.2f%n", avgInvCost));
            w.write(String.format(Locale.US, "costo_personale_eur_gg\t%.2f%n", laborCost));
            w.write(String.format(Locale.US, "costo_totale_eur_gg\t%.2f%n", laborCost + avgInvCost));
        } catch (IOException e) {
            System.err.println("Errore scrittura summary.dat: " + e.getMessage());
        }
    }

    private void generateDatFileCI(double[][] perSlotPerReplica, String centerName, String statName) {
        int numSlots = perSlotPerReplica.length;
        int numRep   = (numSlots > 0) ? perSlotPerReplica[0].length : 0;

        // t(0.025, df=numRep-1) — approssimazione via tabella per valori comuni
        double tCrit = tCritical(numRep - 1, 0.025);

        new File(outDir).mkdirs();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outDir + "/" + centerName + "_" + statName + ".dat"))) {
            writer.write("# mean\tlower_ci\tupper_ci");
            writer.newLine();
            for (int j = 0; j < numSlots; j++) {
                double sum = 0, sum2 = 0;
                for (int k = 0; k < numRep; k++) sum += perSlotPerReplica[j][k];
                double mean = sum / numRep;
                for (int k = 0; k < numRep; k++) {
                    double d = perSlotPerReplica[j][k] - mean;
                    sum2 += d * d;
                }
                double std = (numRep > 1) ? Math.sqrt(sum2 / (numRep - 1)) : 0.0;
                double hw  = tCrit * std / Math.sqrt(numRep);
                writer.write(String.format(java.util.Locale.US, "%.8f\t%.8f\t%.8f", mean, mean - hw, mean + hw));
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static double tCritical(int df, double alpha) {
        // Approssimazione di t_{alpha, df} per df comuni (95% CI, alpha=0.025)
        if (df <= 0)   return 12.706;
        if (df == 1)   return 12.706;
        if (df <= 5)   return 2.571;
        if (df <= 10)  return 2.228;
        if (df <= 20)  return 2.086;
        if (df <= 30)  return 2.042;
        if (df <= 60)  return 2.000;
        if (df <= 120) return 1.980;
        return 1.960; // z_0.025 per df grandi
    }
}
