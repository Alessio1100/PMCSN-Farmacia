package it.farmacia.control;

import it.farmacia.utils.Rvms;
import it.farmacia.utils.Rngs;
import it.farmacia.utils.Rvgs;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

/**
 * Analisi del transitorio — metodo delle repliche indipendenti.
 *
 * Ogni replica chiama Simulation.runTransientAnalysis() che gestisce
 * internamente il loop eventi, esattamente come runInfiniteHorizonSimulation.
 * Il campionamento avviene tramite un callback passato come parametro.
 *
 * Output: tre file .dat in stats/ — una colonna per replica, una riga per punto.
 */
public class TransientAnalysis {

    // ── Parametri ─────────────────────────────────────────────────────────────

    /** Numero di repliche. Con 3-5 il grafico è leggibile. */
    private static final int NUM_REPLICATIONS     = 5;

    /**
     * Punti di campionamento per replica = risoluzione della curva.
     * Con 2000 e intervallo 20 → 40.000 job sull'asse X (esteso da 10k per verificare che il
     * warm-up sia davvero raggiunto; da plot_transient si generano le viste a 10k e a 40k).
     */
    private static final int NUM_OBSERVATIONS     = 2000;

    /**
     * Ogni quanti completamenti campionare E(Ts).
     * 20 → un punto ogni 20 job → alta risoluzione nella fase transiente iniziale.
     */
    private static final int OBSERVATION_INTERVAL = 20;

    /**
     * Seed master da cui vengono derivati i seed di tutte le repliche.
     *
     * Rngs.plantSeeds() separa i seed degli stream di 8.367.782 passi,
     * garantendo non-sovrapposizione matematica tra le repliche.
     * Non si usa mai direttamente per inizializzare una replica.
     */
    private static final long MASTER_SEED        = 123456789L;

    // Config inventario PRE-ottimizzazione (l'ottimizzazione s,S avviene DOPO, in InventoryConfigSearch).
    // Allineata a BatchCalibration (attività 02) = guardrail ~78% S (§19): stesso setup dello studio
    // d'infinito a cui il transitorio fornisce il warm-up. NON usare qui la config scelta {75,65,55,40,30}.
    private static final int[]  INVENTORY_CONFIG  = {60, 55, 45, 45, 25};
    // λ ALLINEATO all'orizzonte infinito (0.0190975 = media NSPP raw): così il warm-up stimato dal
    // transitorio è quello valido per l'infinito (stesso λ). Override con -Dlambda. Era 0.01591.
    private static final double LAMBDA            = Double.parseDouble(System.getProperty("lambda", "0.0190975"));

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        new File("stats/01_transitorio/dat").mkdirs();
        new TransientAnalysis().run();
    }

    public void run() {
        System.out.printf("=== ANALISI DEL TRANSITORIO ===%n");
        System.out.printf("Repliche: %d  |  Punti: %d  |  Intervallo: %d completamenti%n",
                NUM_REPLICATIONS, NUM_OBSERVATIONS, OBSERVATION_INTERVAL);
        System.out.printf("Asse X max: %d completamenti%n%n",
                NUM_OBSERVATIONS * OBSERVATION_INTERVAL);

        // ── Derivazione seed dalle struttura del generatore ───────────────────
        // plantSeeds separa i seed degli stream di 8.367.782 passi →
        // garanzia matematica di non-sovrapposizione tra repliche.
        Rngs seedDerivator = new Rngs();
        seedDerivator.plantSeeds(MASTER_SEED);

        long[] replicaSeeds = new long[NUM_REPLICATIONS];
        for (int r = 0; r < NUM_REPLICATIONS; r++) {
            seedDerivator.selectStream(r);
            replicaSeeds[r] = seedDerivator.getSeed();
        }

        System.out.println("Seed derivati (stream 0.." + (NUM_REPLICATIONS - 1)
                + " da master " + MASTER_SEED + "):");
        for (int r = 0; r < NUM_REPLICATIONS; r++) {
            System.out.printf("  Replica %d → seed %d%n", r + 1, replicaSeeds[r]);
        }
        System.out.println();

        // ── Raccolta dati ─────────────────────────────────────────────────────
        double[][] rawRT = new double[NUM_REPLICATIONS][NUM_OBSERVATIONS];
        double[][] rawUt = new double[NUM_REPLICATIONS][NUM_OBSERVATIONS];
        double[][] rawN  = new double[NUM_REPLICATIONS][NUM_OBSERVATIONS];

        for (int rep = 0; rep < NUM_REPLICATIONS; rep++) {
            System.out.printf("  Replica %d/%d  (seed %d)  ",
                    rep + 1, NUM_REPLICATIONS, replicaSeeds[rep]);

            Simulation.resetNextId();

            // Generatore indipendente per questa replica
            Rngs rngs = new Rngs();
            rngs.plantSeeds(replicaSeeds[rep]);
            Rvgs rvgs = new Rvgs(rngs);

            Simulation sim = new Simulation();
            sim.rvms = new Rvms();
            sim.initSeed(rngs, rvgs);
            sim.setArrivalRateInf(LAMBDA);

            // Buffer locali per questa replica
            double[] obsRT = new double[NUM_OBSERVATIONS];
            double[] obsUt = new double[NUM_OBSERVATIONS];
            double[] obsN  = new double[NUM_OBSERVATIONS];
            int[]    idx   = {0};

            // Esegue la replica — il callback viene chiamato ogni OBSERVATION_INTERVAL
            // completamenti, riceve il contatore corrente come parametro.
            sim.runTransientAnalysis(
                    NUM_OBSERVATIONS,
                    OBSERVATION_INTERVAL,
                    INVENTORY_CONFIG,
                    (completionCount) -> {
                        if (idx[0] >= NUM_OBSERVATIONS) return;
                        int j = idx[0];

                        // E(Ts) cumulativo dall'inizio della replica:
                        // media di tutti i response time raccolti fino a questo job.
                        // È esattamente il valore sull'asse Y del grafico.
                        obsRT[j] = sim.getAvgGlobalResponseTime();
                        obsUt[j] = sim.getAvgUtilizationCasse();
                        obsN[j]  = sim.getAvgJobsInSystem();
                        idx[0]++;
                    }
            );

            System.arraycopy(obsRT, 0, rawRT[rep], 0, NUM_OBSERVATIONS);
            System.arraycopy(obsUt, 0, rawUt[rep], 0, NUM_OBSERVATIONS);
            System.arraycopy(obsN,  0, rawN[rep],  0, NUM_OBSERVATIONS);

            System.out.printf("RT_finale=%.1fs  Ut=%.3f  N=%.2f%n",
                    obsRT[NUM_OBSERVATIONS - 1],
                    obsUt[NUM_OBSERVATIONS - 1],
                    obsN [NUM_OBSERVATIONS - 1]);
        }

        // ── Output ────────────────────────────────────────────────────────────
        writeDatFile("stats/01_transitorio/dat/transient_responseTime.dat", rawRT, replicaSeeds);
        writeDatFile("stats/01_transitorio/dat/transient_utilCasse.dat",    rawUt, replicaSeeds);
        writeDatFile("stats/01_transitorio/dat/transient_numInSystem.dat",  rawN,  replicaSeeds);

        int warmupRT = estimateWarmup(rawRT);
        int warmupUt = estimateWarmup(rawUt);
        int warmupN  = estimateWarmup(rawN);
        int warmup   = Math.max(Math.max(warmupRT, warmupUt), warmupN) * OBSERVATION_INTERVAL;

        System.out.printf("%n=== STIMA WARM-UP ===%n");
        System.out.printf("  Response time e2e  : j*=%3d → %5d completamenti%n",
                warmupRT, warmupRT * OBSERVATION_INTERVAL);
        System.out.printf("  Utilizz. casse     : j*=%3d → %5d completamenti%n",
                warmupUt, warmupUt * OBSERVATION_INTERVAL);
        System.out.printf("  E[N] sistema       : j*=%3d → %5d completamenti%n",
                warmupN,  warmupN  * OBSERVATION_INTERVAL);
        System.out.printf("  Warm-up consigliato: %d completamenti%n", warmup);
        System.out.printf("  warmUpJobs attuale in InfiniteHorizonSimulation: 10000%n");
        System.out.printf(warmup > 10000
                ? "  ⚠  Warm-up stimato > 10000 → aggiornare warmUpJobs.%n"
                : "  ✓  10000 è sufficiente.%n");

        System.out.println("\nFile scritti in stats/:");
        System.out.println("  transient_responseTime.dat");
        System.out.println("  transient_utilCasse.dat");
        System.out.println("  transient_numInSystem.dat");
    }

    // ── Scrittura .dat ────────────────────────────────────────────────────────

    private void writeDatFile(String path, double[][] data, long[] seeds) {
        int nRep = data.length;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            StringBuilder header = new StringBuilder("# completamenti");
            for (int r = 0; r < nRep; r++) header.append("\tseed_").append(seeds[r]);
            bw.write(header.toString());
            bw.newLine();

            for (int j = 0; j < NUM_OBSERVATIONS; j++) {
                StringBuilder row = new StringBuilder();
                row.append((j + 1) * OBSERVATION_INTERVAL);
                for (int r = 0; r < nRep; r++)
                    row.append("\t").append(String.format("%.4f", data[r][j]));
                bw.write(row.toString());
                bw.newLine();
            }
        } catch (IOException e) {
            System.err.println("Errore scrittura " + path + ": " + e.getMessage());
        }
    }

    // ── Stima warm-up ─────────────────────────────────────────────────────────

    private int estimateWarmup(double[][] data) {
        int nRep = data.length, J = data[0].length;
        double[] mean = new double[J];
        for (int j = 0; j < J; j++) {
            double s = 0;
            for (int r = 0; r < nRep; r++) s += data[r][j];
            mean[j] = s / nRep;
        }
        int tail = (int)(J * 0.70);
        double regime = 0;
        for (int j = tail; j < J; j++) regime += mean[j];
        regime /= (J - tail);

        double thr = Math.max(Math.abs(regime) * 0.05, 1e-9);
        for (int j = 0; j <= J - 5; j++) {
            boolean ok = true;
            for (int k = j; k < j + 5; k++)
                if (Math.abs(mean[k] - regime) > thr) { ok = false; break; }
            if (ok) return j;
        }
        return J / 2;
    }
}