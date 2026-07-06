package it.farmacia.control;

import it.farmacia.model.StreamType;
import it.farmacia.utils.Rngs;
import it.farmacia.utils.Rvgs;
import it.farmacia.utils.Rvms;

import java.io.*;
import java.util.*;

/**
 * Ricerca della politica di inventario ottimale a ORIZZONTE FINITO (giornata NSPP).
 *
 * VARIABILI di ottimizzazione (capacità S FISSA — è il banco fisico, §3):
 *   - s = {s1..s5}  soglie di riordino per classe (griglia);
 *   - R             periodo di revisione (30 o 60 min);
 *   - f_init        livello iniziale come frazione di S (apertura banco).
 *
 * METRICHE registrate per ogni configurazione (NESSUNO score pesato: si grafica e si sceglie):
 *   P(OOS) per-articolo, costo inventario (€/giorno), E[T] di sistema (controllo), delivery lag MAX,
 *   frazione di ordini con lag > R.
 *
 * FEASIBILITY (vincoli):
 *   - P(OOS) ≤ 5% (SLA servizio);
 *   - delivery lag ≤ R per OGNI ordine (frazione lag>R = 0): se la revisione è ogni R, il carico di
 *     uno slot DEVE finire entro R, altrimenti si accumula nello slot successivo e satura il braccio
 *     (cricchetto §16/§19). È il criterio di accettabilità soprattutto per R=30 min.
 *
 * Valutazione su NUM_REPLICATIONS repliche con CRN (stesso seed per tutte le config → confronto equo).
 * Staffing casse = PIENO (5 casse, 2 magazzinieri, tutte le fasce): guardrail conservativo, l'inventario
 * deve reggere col peggior caso di smoothing (le casse si ottimizzano dopo, poi si ri-verifica l'OOS).
 *
 * Override: -Dstep (passo griglia s, def 10) -Dreps (repliche, def 16) -Dsla.oos (def 0.05)
 *
 * File: stats/inventory_search/results.dat (tutte) + best_configs.dat (feasible ordinate per costo).
 */
public class InventoryConfigSearch {

    private static final int    NUM_REPLICATIONS = Integer.getInteger("reps", 16);
    private static final int    STEP             = Integer.getInteger("step", 10);
    private static final double SLA_OOS          = Double.parseDouble(System.getProperty("sla.oos", "0.05"));

    // Leve aggiuntive: periodo di revisione e frazione livello iniziale (apertura banco).
    private static final int[]    R_VALUES     = {1800, 3600};   // 30 e 60 min
    private static final double[] FINIT_VALUES = {0.85, 1.0};    // livello iniziale = f·S

    // Griglia soglie s (S fissa come in Simulation.initInventorySystem: {80,70,60,55,35}).
    private static final int S1_MIN = 45, S1_MAX = 80;
    private static final int S2_MIN = 45, S2_MAX = 70;
    private static final int S3_MIN = 45, S3_MAX = 60;
    private static final int S4_MIN = 30, S4_MAX = 55;
    private static final int S5_MIN = 20, S5_MAX = 35;

    // Staffing PIENO per tutte le 24 fasce (guardrail conservativo).
    private static final int[][] CONFIG_CENTERS = new int[24][];
    static { for (int i = 0; i < 24; i++) CONFIG_CENTERS[i] = new int[]{5, 2}; }

    // slotRates: passthrough a setArrivalRate. La GENERAZIONE usa Simulation.arrivalRates (capato),
    // quindi questi valori non guidano gli arrivi; tenuti coerenti col profilo capato per sicurezza.
    private static final double[] SLOT_RATES = {
        0.007050, 0.008615, 0.015500, 0.018335, 0.019800, 0.016200,
        0.022200, 0.025500, 0.024935, 0.020400, 0.012900, 0.015765,
        0.016135, 0.013200, 0.012000, 0.014665, 0.017700, 0.021635,
        0.024000, 0.025500, 0.025500, 0.024900, 0.025500, 0.020000
    };
    private static final int NUM_SAMPLING = 96;

    private static class Res {
        int[] s; int R; double fInit;
        double poos, cost, respT, maxLag, fracOverR;
        boolean feasible;
    }

    public static void main(String[] args) { new InventoryConfigSearch().run(); }

    public void run() {
        // Conta combinazioni
        long sCombos = 0;
        for (int s1=S1_MIN;s1<S1_MAX;s1+=STEP) for (int s2=S2_MIN;s2<S2_MAX;s2+=STEP)
        for (int s3=S3_MIN;s3<S3_MAX;s3+=STEP) for (int s4=S4_MIN;s4<S4_MAX;s4+=STEP)
        for (int s5=S5_MIN;s5<S5_MAX;s5+=STEP) sCombos++;
        long total = sCombos * R_VALUES.length * FINIT_VALUES.length;

        System.out.println("=".repeat(70));
        System.out.println("  RICERCA POLITICA INVENTARIO (s, R, livello iniziale) — orizzonte finito");
        System.out.println("=".repeat(70));
        System.out.printf("  Combinazioni s=%d  × R%s × f_init%s = %d config  |  reps=%d  |  SLA P(OOS)≤%.0f%%%n",
                sCombos, Arrays.toString(R_VALUES), Arrays.toString(FINIT_VALUES), total, NUM_REPLICATIONS, SLA_OOS*100);
        System.out.printf("  Simulazioni totali: %d   (casse PIENE 5,2 tutte le fasce)%n%n", total * NUM_REPLICATIONS);

        // Routing nella cartella numerata del piano esperimenti (step 04). Override con -Dstats.dir.
        String outDir = System.getProperty("stats.dir", "stats/04_inventory_config/dat");
        new File(outDir).mkdirs();
        String outPath = outDir + "/results.dat";
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outPath, false))) {
            bw.write("# s1\ts2\ts3\ts4\ts5\tR_s\tf_init\tP_OOS\tcost_eur\tE_T_s\tmaxLag_s\tfracLagOverR\tfeasible");
            bw.newLine();
        } catch (IOException e) { System.err.println("Errore file: " + e.getMessage()); return; }

        List<Res> all = new ArrayList<>();
        int idx = 0; long startMs = System.currentTimeMillis();

        for (int s1=S1_MIN;s1<S1_MAX;s1+=STEP) for (int s2=S2_MIN;s2<S2_MAX;s2+=STEP)
        for (int s3=S3_MIN;s3<S3_MAX;s3+=STEP) for (int s4=S4_MIN;s4<S4_MAX;s4+=STEP)
        for (int s5=S5_MIN;s5<S5_MAX;s5+=STEP)
        for (int R : R_VALUES) for (double fInit : FINIT_VALUES) {

            idx++;
            int[] s = {s1, s2, s3, s4, s5};

            // CRN: stesso seed di partenza per ogni configurazione
            Rngs r = new Rngs(); r.plantSeeds(123456789);
            Rvgs v = new Rvgs(r);

            double sumPoos=0, sumCost=0, sumResp=0, worstMaxLag=0, worstFracOver=0;
            for (int rep = 0; rep < NUM_REPLICATIONS; rep++) {
                Simulation sim = new Simulation();
                sim.setNumSampling(NUM_SAMPLING);
                sim.rvms = new Rvms();
                sim.verboseOutput = false;
                sim.setStop(43200);
                sim.setReviewSeconds(R);
                sim.setInitLevelFraction(fInit);
                sim.initSeed(r, v);
                r.selectStream(StreamType.STREAM_ARRIVAL); r.random(); // allineamento generatore

                sim.runFiniteHorizonSimulation(CONFIG_CENTERS, SLOT_RATES, s);

                double[] sum = sim.getRunSummary();
                double gen = sum[5];
                double poos = (gen > 0) ? sum[1] / gen : 0.0;   // OOS articoli / articoli richiesti
                sumPoos += poos;
                sumCost += sum[6];
                sumResp += sim.getAvgGlobalResponseTime();
                worstMaxLag   = Math.max(worstMaxLag,   sum[8]);  // peggior lag su tutte le repliche
                worstFracOver = Math.max(worstFracOver, sum[9]);  // peggior frazione lag>R
            }

            Res res = new Res();
            res.s = s.clone(); res.R = R; res.fInit = fInit;
            res.poos = sumPoos / NUM_REPLICATIONS;
            res.cost = sumCost / NUM_REPLICATIONS;
            res.respT = sumResp / NUM_REPLICATIONS;
            res.maxLag = worstMaxLag;
            res.fracOverR = worstFracOver;
            // Feasible: SLA su P(OOS) + lag MAI oltre R (nessun ordine in nessuna replica)
            res.feasible = (res.poos <= SLA_OOS) && (res.fracOverR == 0.0);
            all.add(res);

            appendResult(outPath, res);

            if (idx % 20 == 0 || idx == total) {
                long el = (System.currentTimeMillis() - startMs) / 1000;
                double eta = (idx < total) ? (double) el / idx * (total - idx) : 0;
                System.out.printf("[%5d/%5d] s=%-18s R=%4d f=%.2f | P(OOS)=%.3f cost=%7.1f E[T]=%6.1f maxLag=%5.0fs fr>R=%.3f %s | ETA=%ds%n",
                        idx, total, Arrays.toString(s), R, fInit,
                        res.poos, res.cost, res.respT, res.maxLag, res.fracOverR,
                        res.feasible ? "OK" : "--", (long) eta);
            }
        }

        // ── Feasible ordinate per COSTO (nessuno score: si sceglie poi dal grafico) ──
        List<Res> feasible = new ArrayList<>();
        for (Res x : all) if (x.feasible) feasible.add(x);
        feasible.sort(Comparator.comparingDouble(x -> x.cost));

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("  CONFIG FEASIBLE (P(OOS)≤%.0f%% e lag≤R): %d su %d%n", SLA_OOS*100, feasible.size(), all.size());
        System.out.println("  Top-10 per COSTO inventario crescente:");
        System.out.println("=".repeat(70));
        System.out.printf("  %-18s %5s %5s %8s %9s %8s %9s%n", "s", "R", "f", "P(OOS)", "cost", "E[T]", "maxLag");
        for (int k = 0; k < Math.min(10, feasible.size()); k++) {
            Res x = feasible.get(k);
            System.out.printf("  %-18s %5d %5.2f %8.3f %9.1f %8.1f %8.0fs%s%n",
                    Arrays.toString(x.s), x.R, x.fInit, x.poos, x.cost, x.respT, x.maxLag,
                    k == 0 ? "  <- min costo" : "");
        }
        if (feasible.isEmpty())
            System.out.println("  [ATTENZIONE] Nessuna config feasible: allenta lo SLA o rivedi capacità/R.");

        String bestPath = outDir + "/best_configs.dat";
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(bestPath, false))) {
            bw.write("# rank\ts1\ts2\ts3\ts4\ts5\tR_s\tf_init\tP_OOS\tcost_eur\tE_T_s\tmaxLag_s\tfracLagOverR");
            bw.newLine();
            for (int k = 0; k < feasible.size(); k++) {
                Res x = feasible.get(k);
                bw.write(String.format(Locale.US, "%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.4f\t%.2f\t%.2f\t%.1f\t%.4f",
                        k+1, x.s[0],x.s[1],x.s[2],x.s[3],x.s[4], x.R, x.fInit,
                        x.poos, x.cost, x.respT, x.maxLag, x.fracOverR));
                bw.newLine();
            }
        } catch (IOException e) { System.err.println("Errore best_configs: " + e.getMessage()); }

        long sec = (System.currentTimeMillis() - startMs) / 1000;
        System.out.printf("%n  Completato in %dm %ds%n", sec/60, sec%60);
        System.out.println("  Tutti i risultati : " + outPath);
        System.out.println("  Feasible per costo: " + bestPath);
        System.out.println("  → graficare cost vs P(OOS) (con E[T]/lag) per scegliere il trade-off.");
    }

    private static void appendResult(String path, Res x) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path, true))) {
            bw.write(String.format(Locale.US, "%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.4f\t%.2f\t%.2f\t%.1f\t%.4f\t%d",
                    x.s[0],x.s[1],x.s[2],x.s[3],x.s[4], x.R, x.fInit,
                    x.poos, x.cost, x.respT, x.maxLag, x.fracOverR, x.feasible ? 1 : 0));
            bw.newLine();
        } catch (IOException e) { System.err.println("Errore append: " + e.getMessage()); }
    }
}
