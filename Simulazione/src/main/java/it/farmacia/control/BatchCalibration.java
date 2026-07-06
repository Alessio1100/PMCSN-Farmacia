package it.farmacia.control;

import it.farmacia.utils.Rvms;

import java.util.*;

/**
 * Calibrazione della BATCH SIZE per l'orizzonte infinito, secondo il metodo dei batch means
 * delle slide (lect26, Algorithm 8.4.1 + Guidelines):
 *
 *   - numBatches k = 64 FISSO (slide: k>=32, raccomandato 64) → soglia di Chatfield = 2/sqrt(64)=0.25.
 *   - NESSUN gap inter-batch: run continua, "no points discarded" (metodo puro delle slide).
 *   - Si cerca la batch size b minima tale che i batch means siano ~indipendenti, cioè
 *       |r1|(batch means) < 2/sqrt(k) = 0.25   (regola di Chatfield, Sez. 4.4).
 *
 * VERIFICA PER-CENTRO + GLOBALE: l'IC è valido solo per la serie su cui è costruito. Poiché si
 * riportano statistiche per-centro (response di ogni centro) oltre al globale, qui si richiede che
 * TUTTE quelle serie passino Chatfield: criterio = max|r1| su {globale, 7 centri} < soglia. Domina
 * il centro più persistente (collo di bottiglia o un centro a basso traffico, più rumoroso).
 *
 * Ricerca per RADDOPPIO geometrico di b: r1 su k=64 ha SE ≈ 1/sqrt(64)=0.125 (≈ la soglia stessa),
 * quindi incrementi fini cadrebbero sotto il rumore → solo cambi geometrici muovono r1 in modo
 * rilevabile. Robustezza sul bordo: MARGINE (accetta con max|r1| <= 0.8*soglia) + CONFERMA
 * MULTI-SEED (la b candidata ri-provata con altri seed; tutti devono restare sotto soglia).
 *
 * Override: -Dbm.start (def 1024) -Dbm.max (def 262144) -Dbm.margin (def 0.8) -Dbm.seeds (def 3)
 */
public class BatchCalibration {

    // ── Regola della prof.: k = 64 fisso ──────────────────────────────────────
    private static final int NUM_BATCHES = 64;

    // ── Ricerca adattiva della batch size (raddoppio geometrico) ──────────────
    private static final int    B_START       = Integer.getInteger("bm.start", 1024);
    private static final int    B_MAX         = Integer.getInteger("bm.max", 32768);  // oltre = troppo lento; se non passa qui, il sistema è troppo caldo / non a regime
    private static final double MARGIN_FACTOR = Double.parseDouble(System.getProperty("bm.margin", "0.8"));
    private static final int    CONFIRM_SEEDS = Integer.getInteger("bm.seeds", 3);

    private static final long[] SEEDS = {
            123456789L, 987654321L, 192837465L, 543210987L, 111222333L, 246813579L
    };

    // Etichette: indice 0 = globale, 1..7 = centri (ordine di centers[] in Simulation).
    private static final String[] LABELS = {
            "GLOB", "Casse", "Online", "Disp", "B.Uno", "B.Due", "Mag", "Pag"
    };
    // Centri CLIENTE inclusi nel criterio max|r1| (gli altri si stampano ma non vincolano):
    //   GLOB, Casse, Online, B.Due = inclusi (response cliente, ben comportati).
    //   Disp/Pag = ESCLUSI (deterministici, IC banale). Mag = ESCLUSO (non-cliente).
    //   B.Uno = ESCLUSO perché il response esposto è il BLEND HIGH+LOW (include il carico/
    //   rifornimento, bursty); per includerlo servirebbe esporre il solo arm LOW = prelievo cliente.
    private static final boolean[] INCLUDE = { true, true, true, false, false, true, false, false };

    // ── Parametri di simulazione fissi ────────────────────────────────────────
    // λ = quello di PRODUZIONE dell'orizzonte infinito (media NSPP raw, CLAUDE.md §20.1): la batch
    // size va calibrata allo stesso operating point delle run che la useranno (a λ più alto il
    // sistema è più congestionato → mescola più lentamente → serve b più grande).
    private static final double TEST_LAMBDA = Double.parseDouble(System.getProperty("bm.lambda", "0.0190975")); // -Dbm.lambda per testare λ diversi
    private static final int[]  INVENTORY   = {60, 55, 45, 45, 25}; // guardrail pre-ottimizzazione (attività 01-03)
    private static final int[]  CENTER_CFG  = {5, 2};              // 5 casse, 2 magazzinieri

    // ── Output .dat (attività 02 del piano esperimenti) ───────────────────────
    private static final String OUT_DIR = System.getProperty("stats.dir", "stats/02_batch_calibration/dat");
    private static final int    ACF_MAX_LAG = 16;                  // correlogramma fino a lag 16 (k=64 batch)

    // Batch means (response) dell'ULTIMA probe: [0]=sistema, [1..7]=centri. Servono per salvare
    // serie e correlogramma della b accettata (seed primario).
    private double[]   lastSys;
    private double[][] lastPerC;

    // -Dbm.sweep=true: probe di TUTTE le b nel range SENZA accettazione (solo raccolta |r1|),
    // per il grafico |r1| vs b intorno alla b scelta dalla ricerca canonica.
    private static final boolean SWEEP = Boolean.parseBoolean(System.getProperty("bm.sweep", "false"));

    public static void main(String[] args) {
        if (SWEEP) new BatchCalibration().runSweep();
        else       new BatchCalibration().run();
    }

    /**
     * Modalità SWEEP: campiona |r1| per ogni b in [B_START, B_MAX] (raddoppio) su nConfirm seed,
     * senza logica di accettazione. Scrive SOLO chatfield_landscape.dat (non tocca i file della
     * ricerca canonica): serve da evidenza grafica che sotto la b scelta il test fallisce/il
     * margine non regge, e sopra resta sotto soglia.
     */
    private void runSweep() {
        final double threshold = 2.0 / Math.sqrt(NUM_BATCHES);
        final double target    = MARGIN_FACTOR * threshold;
        final int    nConfirm  = Math.min(CONFIRM_SEEDS, SEEDS.length);

        System.out.printf("=== SWEEP |r1| vs b (diagnostico, nessuna accettazione): b=%d..%d, %d seed, lambda=%.5f ===%n",
                B_START, B_MAX, nConfirm, TEST_LAMBDA);
        List<double[]> trace = new ArrayList<>();
        for (int b = B_START; b <= B_MAX; b *= 2) {
            for (int s = 0; s < nConfirm; s++) {
                double[] r1 = probe(b, SEEDS[s]);
                trace.add(traceRow(b, s, r1));
                System.out.printf("[b=%6d seed%d] max|r1|=%.4f   %s%n",
                        b, s, r1[argMaxIncluded(r1)], breakdown(r1));
            }
        }
        java.io.File dir = new java.io.File(OUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Impossibile creare " + OUT_DIR);
            return;
        }
        java.io.File f = new java.io.File(dir, "chatfield_landscape.dat");
        writeTraceDat(f, trace, threshold, target,
                "sweep diagnostico |r1| vs b (tutte le b, nessuna accettazione)");
        System.out.println("\n  scritto " + f.getPath());
    }

    // ── Run principale: ricerca per raddoppio sul max r1 (globale + centri) ────

    public void run() {
        final double threshold = 2.0 / Math.sqrt(NUM_BATCHES);   // = 0.25
        final double target    = MARGIN_FACTOR * threshold;       // margine (def 0.20)
        final int    nConfirm  = Math.min(CONFIRM_SEEDS, SEEDS.length);

        System.out.println("=== CALIBRAZIONE BATCH SIZE – BATCH MEANS (slide lect26), CHATFIELD PER-CENTRO ===");
        System.out.printf("k = %d FISSO   soglia = 2/sqrt(%d) = %.4f   gap = 0 (metodo puro)%n",
                NUM_BATCHES, NUM_BATCHES, threshold);
        System.out.printf("Criterio: max|r1| sui CENTRI CLIENTE inclusi <= margine %.2f*soglia = %.4f%n",
                MARGIN_FACTOR, target);
        System.out.printf("Conferma su %d seed   |   ricerca b: raddoppio %d..%d   (lambda=%.5f, %d casse %d mag)%n%n",
                nConfirm, B_START, B_MAX, TEST_LAMBDA, CENTER_CFG[0], CENTER_CFG[1]);

        List<double[]> log = new ArrayList<>();   // {b, max|r1|}
        List<double[]> trace = new ArrayList<>(); // righe per chatfield_search.dat: {b, seedIdx, r1[0..7]}
        int accepted = -1;
        double[] acceptedR1 = null;
        double[]   acceptedSys  = null;           // batch means alla b accettata (seed primario)
        double[][] acceptedPerC = null;

        for (int b = B_START; b <= B_MAX; b *= 2) {
            double[] r1 = probe(b, SEEDS[0]);
            double[]   primSys  = lastSys;        // serie del seed primario a questa b
            double[][] primPerC = lastPerC;
            int    arg  = argMaxIncluded(r1);
            double maxR1 = r1[arg];
            log.add(new double[]{b, maxR1});
            trace.add(traceRow(b, 0, r1));

            boolean primaryOk = maxR1 <= target;
            System.out.printf("[b=%6d] max|r1|=%.4f (%-6s)  %s%n   %s%n",
                    b, maxR1, LABELS[arg], primaryOk ? "<= margine -> confermo" : "> margine -> raddoppio",
                    breakdown(r1));

            if (!primaryOk) continue;

            // Conferma multi-seed: il max|r1| di ogni seed deve restare SOTTO soglia (0.25)
            boolean allOk = true;
            StringBuilder sb = new StringBuilder();
            for (int s = 1; s < nConfirm; s++) {
                double[] r1s = probe(b, SEEDS[s]);
                trace.add(traceRow(b, s, r1s));
                double maxs  = r1s[argMaxIncluded(r1s)];
                sb.append(String.format(Locale.US, "  seed%d:max=%.4f%s", s, maxs, maxs < threshold ? "" : "(FAIL)"));
                if (maxs >= threshold) allOk = false;
            }
            if (nConfirm > 1) System.out.printf("   conferma:%s  -> %s%n", sb, allOk ? "OK" : "una sopra soglia, raddoppio");

            if (allOk) { accepted = b; acceptedR1 = r1; acceptedSys = primSys; acceptedPerC = primPerC; break; }
        }

        printSummary(log, threshold, target, accepted, acceptedR1);
        writeDatFiles(trace, accepted, acceptedR1, acceptedSys, acceptedPerC, threshold, target, nConfirm);
    }

    /** Riga per chatfield_search.dat: {b, seedIdx, r1[0..7]}. */
    private static double[] traceRow(int b, int seedIdx, double[] r1) {
        double[] row = new double[2 + r1.length];
        row[0] = b; row[1] = seedIdx;
        System.arraycopy(r1, 0, row, 2, r1.length);
        return row;
    }

    // ── Singola prova: una run infinita; ritorna |r1| per [globale, 7 centri] ──

    private double[] probe(int batchSize, long seed) {
        Simulation sim = new Simulation();
        sim.rvms = new Rvms();
        sim.infiniteSeed = seed;
        sim.setArrivalRateInf(TEST_LAMBDA);
        sim.runInfiniteHorizonSimulation(NUM_BATCHES, batchSize, INVENTORY, CENTER_CFG, false, 0); // gap = 0

        double[]   sys  = sim.lastRunResult.batchSysRT;
        double[][] perC = sim.lastRunResult.batchRespPerCenter;   // [7][k]

        // Conserva le serie (copia) per i .dat della b accettata (seed primario).
        lastSys  = sys.clone();
        lastPerC = new double[perC.length][];
        for (int c = 0; c < perC.length; c++) lastPerC[c] = perC[c].clone();

        double[] r1 = new double[1 + perC.length];               // [0]=globale, [1..]=centri
        r1[0] = Math.abs(lag1Acf(sys));
        for (int c = 0; c < perC.length; c++) r1[c + 1] = Math.abs(lag1Acf(perC[c]));
        return r1;
    }

    /**
     * Stima di Chatfield del lag-1 = c1/c0 con prodotti CENTRATI (come Acs/Leemis):
     *   c0 = Σ (x_i - m)^2 ,  c1 = Σ (x_i - m)(x_{i+1} - m) ,  r1 = c1/c0  (il /n si semplifica).
     * ⚠️ I prodotti vanno centrati PRIMA di moltiplicare: la forma "Σx_i x_{i+1}/(n-1) - m^2" soffre
     * di cancellazione catastrofica quando m >> std (qui m≈481, std≈13) e produce r1 spuri ~0.9 su
     * serie che sono in realtà rumore bianco (bug che faceva fallire la calibrazione). 0 se ~costante.
     */
    private static double lag1Acf(double[] x) {
        int n = x.length;
        if (n < 2) return 0.0;
        double mean = 0.0;
        for (double v : x) mean += v;
        mean /= n;
        double c0 = 0.0, c1 = 0.0;
        for (int i = 0; i < n; i++) {
            double d = x[i] - mean;
            c0 += d * d;
            if (i < n - 1) c1 += d * (x[i + 1] - mean);
        }
        if (c0 <= 1e-9) return 0.0;                  // serie ~costante → nessuna autocorrelazione (es. centri deterministici)
        double r = c1 / c0;
        return Math.max(-1.0, Math.min(1.0, r));     // clamp: la stima campionaria può sforare [-1,1] su serie quasi-degeneri
    }

    /** argmax dell'|r1| SOLO tra i centri inclusi nel criterio (INCLUDE). */
    private static int argMaxIncluded(double[] a) {
        int arg = -1;
        for (int i = 0; i < a.length; i++)
            if (INCLUDE[i] && (arg < 0 || a[i] > a[arg])) arg = i;
        return arg;
    }

    private static String breakdown(double[] r1) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r1.length; i++) {
            String lbl = INCLUDE[i] ? LABELS[i] : "[" + LABELS[i] + "]"; // [..] = escluso dal criterio
            sb.append(String.format(Locale.US, "%s=%.3f ", lbl, r1[i]));
        }
        return sb.toString().trim();
    }

    // ── Riepilogo finale ──────────────────────────────────────────────────────

    private void printSummary(List<double[]> log, double threshold, double target,
                              int accepted, double[] acceptedR1) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  RIEPILOGO RICERCA (raddoppio, criterio max|r1| globale+centri)");
        System.out.println("=".repeat(70));
        System.out.printf("  %-10s %-12s %-10s%n", "batchSize", "max|r1|", "vs margine");
        System.out.println("  " + "-".repeat(36));
        for (double[] e : log)
            System.out.printf("  %-10d %-12.4f %-10s%n", (int) e[0], e[1], e[1] <= target ? "<= margine" : "> margine");
        System.out.println();

        if (accepted < 0) {
            System.out.printf("  [FAIL] Nessuna b <= %d passa con margine+conferma su tutti i centri.%n", B_MAX);
            System.out.println("         Alza -Dbm.max; o se 'comanda' un centro a basso traffico è rumore (alza b o");
            System.out.println("         riporta quel centro come stima puntuale, IC solo dove Chatfield passa).");
            return;
        }

        long totalJobs = (long) NUM_BATCHES * accepted;
        int  arg = argMaxIncluded(acceptedR1);
        System.out.println("  +-- CONFIGURAZIONE CONSIGLIATA (da slide) -----------------------+");
        System.out.printf( "  | numBatches (k) = %-5d   batchSize (b) = %-6d   gap = 0       |%n", NUM_BATCHES, accepted);
        System.out.printf( "  | max|r1| = %.4f (%-6s) < soglia %.4f  (margine %.4f)       |%n",
                acceptedR1[arg], LABELS[arg], threshold, target);
        System.out.printf( "  | totalJobs = k*b = %-10d  (+ warm-up interno)              |%n", totalJobs);
        System.out.println("  +----------------------------------------------------------------+");
        System.out.printf("  r1 per serie alla b scelta:  %s%n", breakdown(acceptedR1));
        System.out.printf("%n  -> Usa: numBatches=%d, batchSize=%d, gap=0 nelle run a orizzonte infinito.%n",
                NUM_BATCHES, accepted);
    }

    // ── Output .dat (attività 02): evidenza della ricerca + autocorrelazioni ──

    /**
     * Scrive in OUT_DIR:
     *  - chatfield_search.dat   : |r1| per serie ad ogni probe (b, seed) → grafico |r1| vs b;
     *  - acf_batchmeans_b<b>.dat: correlogramma r_j (j=1..ACF_MAX_LAG) dei batch means alla b
     *                             accettata (seed primario) → i lag devono stare in ±2/√k;
     *  - batchmeans_b<b>.dat    : le serie dei batch means stesse (evidenza grezza);
     *  - _run_info.txt          : parametri e risultato della calibrazione.
     */
    private void writeDatFiles(List<double[]> trace, int accepted, double[] acceptedR1,
                               double[] acceptedSys, double[][] acceptedPerC,
                               double threshold, double target, int nConfirm) {
        java.io.File dir = new java.io.File(OUT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Impossibile creare " + OUT_DIR + ": .dat non scritti");
            return;
        }

        String cols = String.join("\t", LABELS);          // GLOB Casse Online Disp B.Uno B.Due Mag Pag

        // 1) traccia della ricerca (tutte le probe, seed primario + conferme)
        writeTraceDat(new java.io.File(dir, "chatfield_search.dat"), trace, threshold, target,
                "traccia della ricerca per raddoppio (seed 0 = primario, altri = conferma)");

        if (accepted < 0 || acceptedSys == null) return;  // ricerca fallita: solo la traccia

        // 2) correlogramma dei batch means alla b accettata (seed primario)
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.File(dir, "acf_batchmeans_b" + accepted + ".dat"))) {
            pw.printf("# ACF dei batch means (k=%d) alla b accettata=%d, seed=%d, lambda=%.7f%n",
                    NUM_BATCHES, accepted, SEEDS[0], TEST_LAMBDA);
            pw.printf("# banda di rumore: |r_j| < 2/sqrt(k) = %.4f (Chatfield vincola il lag-1)%n", threshold);
            pw.printf("# lag\t%s%n", cols);
            double[][] acfs = new double[1 + acceptedPerC.length][];
            acfs[0] = acf(acceptedSys, ACF_MAX_LAG);
            for (int c = 0; c < acceptedPerC.length; c++) acfs[c + 1] = acf(acceptedPerC[c], ACF_MAX_LAG);
            for (int j = 1; j <= ACF_MAX_LAG; j++) {
                StringBuilder sb = new StringBuilder().append(j);
                for (double[] a : acfs) sb.append(String.format(Locale.US, "\t%.6f", a[j - 1]));
                pw.println(sb);
            }
        } catch (Exception e) {
            System.err.println("Errore scrittura acf_batchmeans: " + e.getMessage());
        }

        // 3) serie dei batch means (evidenza grezza, response in secondi)
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.File(dir, "batchmeans_b" + accepted + ".dat"))) {
            pw.printf("# batch means del response (s) alla b accettata=%d, seed=%d%n", accepted, SEEDS[0]);
            pw.printf("# batch\t%s%n", cols);
            for (int b = 0; b < acceptedSys.length; b++) {
                StringBuilder sb = new StringBuilder().append(b);
                sb.append(String.format(Locale.US, "\t%.6f", acceptedSys[b]));
                for (double[] serie : acceptedPerC)
                    sb.append(String.format(Locale.US, "\t%.6f", b < serie.length ? serie[b] : 0.0));
                pw.println(sb);
            }
        } catch (Exception e) {
            System.err.println("Errore scrittura batchmeans: " + e.getMessage());
        }

        // 4) run info
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File(dir, "_run_info.txt"))) {
            pw.println("scenario     = 02_BATCH_CALIBRATION");
            pw.println("timestamp    = " + java.time.LocalDateTime.now());
            pw.printf ("lambda       = %.7f  (= media NSPP raw, lambda di produzione infinito)%n", TEST_LAMBDA);
            pw.printf ("k (fisso)    = %d   soglia Chatfield = 2/sqrt(k) = %.4f   margine = %.2f*soglia = %.4f%n",
                    NUM_BATCHES, threshold, target);
            pw.printf ("ricerca b    = raddoppio %d..%d, gap = 0 (metodo puro slide lect26)%n", B_START, B_MAX);
            pw.printf ("conferma     = %d seed %s%n", nConfirm, Arrays.toString(Arrays.copyOf(SEEDS, nConfirm)));
            pw.printf ("inventario   = %s   staffing = %d casse, %d mag%n",
                    Arrays.toString(INVENTORY), CENTER_CFG[0], CENTER_CFG[1]);
            pw.printf ("serie incluse nel criterio = {%s}%n", includedLabels());
            pw.printf ("RISULTATO    : b = %d  (k=%d, gap=0)   max|r1| = %.4f%n",
                    accepted, NUM_BATCHES, acceptedR1[argMaxIncluded(acceptedR1)]);
            pw.printf ("r1 per serie : %s%n", breakdown(acceptedR1));
        } catch (Exception e) {
            System.err.println("Errore scrittura _run_info.txt: " + e.getMessage());
        }

        System.out.println("\n  .dat scritti in " + dir.getPath()
                + " (chatfield_search, acf_batchmeans_b" + accepted + ", batchmeans_b" + accepted + ", _run_info)");
    }

    /** Scrive una traccia di probe {b, seed, r1[0..7]} con header autodescrittivo. */
    private static void writeTraceDat(java.io.File file, List<double[]> trace,
                                      double threshold, double target, String descr) {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(file)) {
            pw.printf("# %s%n", descr);
            pw.printf("# k=%d fisso, gap=0, lambda=%.7f, soglia=2/sqrt(k)=%.4f, margine=%.2f*soglia=%.4f%n",
                    NUM_BATCHES, TEST_LAMBDA, threshold, MARGIN_FACTOR, target);
            pw.printf("# criterio: max|r1| sulle serie INCLUSE {%s}; le altre sono diagnostiche%n", includedLabels());
            pw.printf("# b\tseed\t%s\tmax_incl%n", String.join("\t", LABELS));
            for (double[] row : trace) {
                double[] r1 = Arrays.copyOfRange(row, 2, row.length);
                StringBuilder sb = new StringBuilder();
                sb.append((int) row[0]).append('\t').append((int) row[1]);
                for (double v : r1) sb.append(String.format(Locale.US, "\t%.6f", v));
                sb.append(String.format(Locale.US, "\t%.6f", r1[argMaxIncluded(r1)]));
                pw.println(sb);
            }
        } catch (Exception e) {
            System.err.println("Errore scrittura " + file.getName() + ": " + e.getMessage());
        }
    }

    private static String includedLabels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LABELS.length; i++)
            if (INCLUDE[i]) sb.append(sb.length() > 0 ? ", " : "").append(LABELS[i]);
        return sb.toString();
    }

    /**
     * Funzione di autocorrelazione r_j = c_j/c_0 (j=1..K) con prodotti CENTRATI, stessa
     * convenzione di {@link #lag1Acf} e di Acs/Leemis (r_1 coincide con lag1Acf).
     */
    private static double[] acf(double[] x, int K) {
        int n = x.length;
        double[] r = new double[K];
        if (n < 2) return r;
        double mean = 0.0;
        for (double v : x) mean += v;
        mean /= n;
        double c0 = 0.0;
        for (double v : x) c0 += (v - mean) * (v - mean);
        if (c0 <= 1e-9) return r;
        for (int j = 1; j <= K && j < n; j++) {
            double cj = 0.0;
            for (int i = 0; i + j < n; i++) cj += (x[i] - mean) * (x[i + j] - mean);
            r[j - 1] = Math.max(-1.0, Math.min(1.0, cj / c0));
        }
        return r;
    }
}
