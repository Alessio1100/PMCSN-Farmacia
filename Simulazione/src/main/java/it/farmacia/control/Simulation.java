package it.farmacia.control;

import it.farmacia.centers.*;
import it.farmacia.events.Event;
import it.farmacia.events.EventType;
import it.farmacia.model.*;
import it.farmacia.utils.Acs;
import it.farmacia.utils.Rngs;
import it.farmacia.utils.Rvgs;
import it.farmacia.utils.Rvms;


import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;


public class Simulation {

    private Queue<Event> events;                            //lista che tiene traccia degli eventi generati durante la simulazione
    private double arrival = 0;
    private double STOP = 43200;
    // Orizzonte infinito: la simulazione è NON-terminante (termina per numero di batch/job, non per
    // tempo). Questo "stop" serve solo a limitare la catena di TIMECHANGE/revisione scorte: va tenuto
    // ABBONDANTEMENTE oltre la durata di qualunque run, altrimenti la revisione (s,S) smette di essere
    // schedulata e l'inventario si svuota (collasso deterministico, indipendente dalla capacità).
    // Es.: nb=64 × bs=262144 ≈ 1.05e9 s simulati → 1e12 copre con larghissimo margine.
    private double STOP_INFINITE = 1.0e12;                             //"close the door" --> il flusso di arrivo viene interrotto
    public boolean closeTheDoor = false;
    private double arrivalRate = 0;
    public int total;
    private double firstArriveSystem = 0;
    private double lastDepartureSystem = 0;
    private int rejectedJob = 0;
    // Slot di 30 minuti
    private static final int INVENTORY_SLOT_SECONDS = 1800;
    // Dimensione massima di un ordine di rifornimento al magazziniere: i riordini più grandi vengono
    // SPEZZATI in più ordini ≤ MAX_REORDER_CHUNK unità, così il magazziniere parallelizza (2 serventi)
    // e il braccio fa carichi piccoli → delivery lag entro la fascia di 30 min e meno attesa in coda.
    private static final int MAX_REORDER_CHUNK = 20;
    // Revisione scorte (s,S) e generazione riordini ogni 30 min (= INVENTORY_SLOT_SECONDS): più
    // reattiva sulla domanda ⚠ ma il delivery lag (~55-90 min, raffica alla revisione) supera R → i
    // carichi si accumulano tra slot. Leva per rientrare: de-burstare i riordini (vedi analisi lag).
    // Periodo di revisione (R) della politica (R,s,S). Default 3600 (60 min) da -Dreview, ma è un
    // CAMPO D'ISTANZA settabile (InventoryConfigSearch lo varia per configurazione: 1800 o 3600).
    // Deve essere multiplo del tick TIMECHANGE = 1800. R più corto alza il tetto di portata del
    // riordino (∝ 1/R) MA richiede delivery lag < R (altrimenti i carichi si accumulano tra slot).
    public int reviewSeconds = Integer.getInteger("review", 1800);
    public void setReviewSeconds(int r) { this.reviewSeconds = r; }

    // Frazione del livello iniziale rispetto a S (apertura del banco): se > 0, livello_iniziale = f·S
    // per ogni classe; se <= 0 usa i valori di default. Variabile di ottimizzazione (InventoryConfigSearch).
    public double initLevelFraction = -1.0;
    public void setInitLevelFraction(double f) { this.initLevelFraction = f; }

    // ── Routing dispatcher → bracci (parametrico) ──────────────────────────────
    //  route.mode = "overflow" (default, versione corrente, stato-dipendente)
    //             | "fixed"    (scheduling astratto: prob. p_b1 verso Braccio Uno)
    //  route.pb1  = probabilita' fissa di instradare un frammento cliente a Braccio Uno
    //  route.window = se true, nei primi PROTECTED_WINDOW_SECONDS di ogni ciclo
    //                 inventario il routing fisso forza 0% verso Braccio Uno (finestra
    //                 protetta schedulata).
    public static final String  ROUTE_MODE   = System.getProperty("route.mode", "fixed");
    public static final double  ROUTE_PB1    = Double.parseDouble(System.getProperty("route.pb1", "0.20"));
    public static final boolean ROUTE_WINDOW = Boolean.parseBoolean(System.getProperty("route.window", "true"));
    private static final double PROTECTED_WINDOW_SECONDS = 600.0; // primi 10 minuti del ciclo
    // ABBANDONI (reneging alle casse fisiche): DISABILITATI di default. Lo studio è focalizzato
    // sull'INVENTORY: senza reneging tutta la domanda generata raggiunge i bracci (nessuna perdita
    // alle casse) → la domanda all'inventory = processo di arrivi conservato e ben definito,
    // decoupled dalla congestione delle casse. Riattivabile con -Dabandonment=true (modello con
    // reneging a mistura di normali troncate). Se false, nessun evento ABANDON viene schedulato.
    public static final boolean ABANDONMENT_ENABLED = Boolean.parseBoolean(System.getProperty("abandonment", "false"));
    // Variabile per tracciare quando l'ultimo slot del nastro trasportatore è occupato
    private double lastScheduledArrivalBraccio1 = 0;
    // ID univoco per ordini interni dell’inventory system
    private int nextInternalOrderId = 500_000_000;
    // === Infinite horizon: λ statico ===
    private double lambdaInf = 0.0;
    private boolean infiniteArrivalMode = false;

    private Time t;                                         //clock di simulazione
    private Rngs r;                                         //generatore di valori randomici Uniform(0,1)
    private Rvgs v;
    private Center[] centers;
    private BraccioDue braccioDue;
    private CassaOnline cassaOnline;
    private Casse casse;
    private InventorySystem inventorySystem;
    private PriorityQueueCenter braccioUno;
    private Dispatcher dispatcher;
    private Magazziniere magazziniere;
    private Pagamento pagamento;
    private double[] classCdf; // CDF estrazione classe farmaco ∝ capacità S (bilanciamento domanda)
    Rvms rvms;
    private int maxNumCasse = 5;
    public int numSampling;
    double[] meanServiceTimes;
    private int arrivalsCasse = 0;
    private int arrivalsCassaOnline = 0;
    Statistics globalStatistics;
    Statistics[][] matrix;
    int idMagazziniere= 1000000;
    private int leavingJob = 0;
    public int newServerIndex;
    private Map<Integer,Double> arrivalTimeCasse = new HashMap<>();
    private Map<Integer,Double> arrivalTimeDispatcher = new HashMap<>();
    private Map<Integer,Double> arrivalTimeCasseOnline = new HashMap<>();
    private Map<Integer,Double> arrivalTimeBraccio1 = new HashMap<>();
    private Map<Integer,Double> arrivalTimeBraccio2 = new HashMap<>();
    private Map<Integer,Double> arrivalTimeBraccio = new HashMap<>();
    private Map<Integer,Double> arrivalTimeMagazziniere = new HashMap<>();
    private Map<Integer,Double> arrivalTimePagamento = new HashMap<>();
    // Classi dei K farmaci di ciascun ORDINE cliente (opzione b: classi diverse nello stesso
    // ordine). Keyed sull'id dell'ordine (preservato lungo tutto il percorso). Popolata
    // all'arrivo, consumata al braccio (consumo inventario per-articolo) e poi rimossa.
    private Map<Integer,int[]> orderClassi = new HashMap<>();
    private List<Double> listPagamentoResponse = new ArrayList<>();
    private List<Double> listGlobalResponse = new ArrayList<>();
    // Metodo B (timestamp diretto): tempo end-to-end di OGNI cliente = uscita dal sistema − ingresso
    // (ingresso = arrivo a cassa fisica O online; uscita = CassePagamento per i fisici, braccio per
    // gli online che saltano il pagamento). Usato per confrontarlo con la somma dei W per-centro.
    private List<Double> listSystemResponse = new ArrayList<>();
    private List<Double> listCasseResponse = new ArrayList<>();
    private List<Double> listCasseOnlineResponse = new ArrayList<>();
    private List<Double> listDispatcherResponse = new ArrayList<>();
    private List<Double> listBraccio1Response = new ArrayList<>();
    private List<Double> listBraccio2Response = new ArrayList<>();
    private List<Double> listBraccioResponse = new ArrayList<>();
    private List<Double> listMagazziniereResponse = new ArrayList<>();
    public double media;
    public double mediaC;
    public double mediaO;
    public double mediaD;
    public double mediaB1;
    public double mediaB2;
    public double mediaM;
    private double alpha = 0.0009;
    int currentServers;
    public double config_resp = 0;
    public double config_cost = 0;
    private List<Integer> listBloccati = new ArrayList<>();
    //public double[] arrivalTimeById = new double[10000000];
    // [REFACTOR phase-type/ordine] Rimossi jobFragmentsRemaining(/Mag), jobToCassa, jobToMag,
    // jobsSpacchettati, listAlreadyMarked, failedParentJobs: servivano al fork-join (split in K
    // frammenti) e al rilascio bloccante della cassa/magazziniere. Ora un ordine è UN solo job
    // e ogni centro è liberato al PROPRIO departure → niente più join, niente più blocco.
    private Map<Integer, Integer> jobToOnline = new HashMap<>();
    private List<Integer> fakeAbandon;
    private int farmaciPersi;
    private List<Integer> notCompleted;
    public List<Integer> listOutOfStock = new ArrayList<>() ;
    public int mId=0;

    // Tempo dell'ultimo campionamento — usato per calcolare la durata della finestra corrente
    private double snapTime = 0.0;

    // ── Risultato dell'ultima run infinite-horizon (popolato da runInfiniteHorizonSimulation) ──
    public RunResult lastRunResult;

    /** Raccoglie le metriche aggregate di una run infinite-horizon per fascia/configurazione. */
    public static class RunResult {
        public double   sysResponseTime;
        public double   lossProbability;      // = abandonProbability + oosProbability (combinata)
        public double   abandonProbability;   // totalReneging / totalGeneratedJob      (per-ORDINE)
        public double   oosProbability;       // totalOutOfStock / totalGeneratedFarmaci (per-ARTICOLO, in [0,1])
        public double   utilCasse;
        public double   utilMagazziniere;
        public double[] batchSysRT;
        public double[] batchLossProb;
        public double[] batchAbandonProb;     // per-batch abandon rate
        public double[] batchOosProb;         // per-batch P(OOS) per-ARTICOLO = OOS / farmaci richiesti
        public double[] batchUtilCasse;
        public double[] batchUtilMag;
        public double[][] batchRespPerCenter; // [centro][batch]: response (W nodo) per-centro, per Chatfield per-centro

        // ── Aggiunte per ConfigurationSearch (§21.6): ρ per centro, split B1, throughput ──
        public String[] centerNames;      // nomi dei centri, stesso ordine di utilPerCenter (centers[])
        public double[] utilPerCenter;    // rho medio sui batch per centro (multi-server = max tra i server)
        public double   utilBraccioUnoHigh; // split carico/rifornimento (coda HIGH) del Braccio Uno
        public double   utilBraccioUnoLow;  // split prelievi/cliente (coda LOW) del Braccio Uno
        public double   throughput;         // ordini completati / secondo, misurato post warm-up
    }

    // Arrival rates per secondo per ciascuna fascia oraria da 30 minuti
    // Tassi di arrivo per fascia (NSPP). Questo campo è la sorgente EFFETTIVA degli arrivi sia per
    // run() che per runFiniteHorizonSimulation (entrambi usano generateAllExternalArrivals che legge
    // questo array). I valori erano gonfiati del +20%: li riporto ai valori ORIGINALI della relazione
    // dividendoli per 1.2 (Java valuta l'espressione a compile-time), così il sistema non satura.
    double[] arrivalRates = {
            0.007050,      // slot  0 (08:00-08:30)
            0.008615000,   // slot  1 (08:30-09:00)
            0.015500000,   // slot  2 (09:00-09:30)
            0.018335000,   // slot  3 (09:30-10:00)
            0.019800,      // slot  4 (10:00-10:30)
            0.016200,      // slot  5 (10:30-11:00)
            0.022200,      // slot  6 (11:00-11:30)
            0.025500000,   // slot  7 (11:30-12:00)  [cap a 0.0255: evita saturazione B2 al picco]
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
            0.025500000,   // slot 19 (17:30-18:00)  [cap a 0.0255]
            0.025500000,   // slot 20 (18:00-18:30)  [cap a 0.0255: era il picco assoluto 0.030435]
            0.024900,      // slot 21 (18:30-19:00)
            0.025500,      // slot 22 (19:00-19:30)
            0.020000000,   // slot 23 (19:30-20:00)
    };


    public static void main(String[] args) {
        Simulation simulation = new Simulation();
        //simulation.setArrivalRate(0.05);
        simulation.run(new int[]{60, 55, 45, 45, 25}); //simulation.run(new int[]{55, 50, 50, 45, 25});
    }

    /**
     * @deprecated NON USARE per la ricerca inventario. Usa {@link InventoryConfigSearch}: questa
     * versione gira {@code run()} a SINGOLA replica (rumorosa, no IC, no NSPP/staffing per fascia,
     * λ costante 0.05) e con uno score pesato response/costo non difendibile. Tenuta solo per storico.
     */
    @Deprecated
    public static void main_config(String[] args) {

        // Limiti massimi
        int S1 = 80; int S2 = 70; int S3 = 60; int S4 = 55; int S5 = 35;
        // Limiti minimi
        int min1 = 45; int min2 = 45; int min3 = 45; int min4 = 30; int min5 = 20;

        int conf = 1;

        // Strutture per salvare i dati temporanei senza classi esterne
        // Ogni array contiene: {s1, s2, s3, s4, s5, responseTime, totalCost}
        List<double[]> allResults = new ArrayList<>();

        // Variabili per la normalizzazione (Min/Max globali)
        double minResp = Double.MAX_VALUE, maxResp = Double.MIN_VALUE;
        double minCost = Double.MAX_VALUE, maxCost = Double.MIN_VALUE;

        // --- FASE 1: ESECUZIONE SIMULAZIONI ---
        for (int s1 = min1; s1 < S1; s1 += 5) {
            for (int s2 = min2; s2 < S2; s2 += 5) {
                for (int s3 = min3; s3 < S3; s3 += 5) {
                    for (int s4 = min4; s4 < S4; s4 += 5) {
                        for (int s5 = min5; s5 < S5; s5 += 5) {

                            int[] config = {s1, s2, s3, s4, s5};

                            System.out.printf("(%d) Testing config: s1=%d, s2=%d, s3=%d, s4=%d, s5=%d%n",
                                    conf++, s1, s2, s3, s4, s5);

                            Simulation simulation = new Simulation();
                            simulation.setArrivalRate(0.05);
                            simulation.run(config);

                            double currentResp = simulation.config_resp;
                            double currentCost = simulation.config_cost;

                            // Aggiorna i Minimi e Massimi globali
                            if (currentResp < minResp) minResp = currentResp;
                            if (currentResp > maxResp) maxResp = currentResp;
                            if (currentCost < minCost) minCost = currentCost;
                            if (currentCost > maxCost) maxCost = currentCost;

                            // Salviamo i risultati in memoria: i primi 5 sono la config, poi Resp, poi Cost
                            allResults.add(new double[]{
                                    (double)s1, (double)s2, (double)s3, (double)s4, (double)s5,
                                    currentResp, currentCost
                            });

                            // Scrittura su file (log)
                            try (BufferedWriter writer = new BufferedWriter(new FileWriter("Inventory_configs.dat", true))) {
                                writer.write("{" + s1 + "," + s2 + "," + s3 + "," + s4 + "," + s5 + "} ; " + currentResp + " ; " + currentCost);
                                writer.newLine();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        // --- FASE 2: CALCOLO SCORE E SCELTA MIGLIORE ---
        System.out.println("Calcolo dello score migliore...");

        double[] bestConfigData = null;
        double bestScore = Double.MAX_VALUE;

        // PESO: 0.5 = Bilanciato.
        // Metti 0.8 se ti interessa molto il tempo e poco i soldi.
        // Metti 0.2 se vuoi risparmiare anche a costo di essere lento.
        double w = 0.5;

        // Evita divisione per zero se tutti i valori sono uguali
        double rangeResp = (maxResp - minResp == 0) ? 1.0 : (maxResp - minResp);
        double rangeCost = (maxCost - minCost == 0) ? 1.0 : (maxCost - minCost);

        for (double[] row : allResults) {
            double rTime = row[5]; // Tempo è all'indice 5
            double rCost = row[6]; // Costo è all'indice 6

            // Normalizzazione (0 = migliore possibile, 1 = peggiore possibile)
            double normTime = (rTime - minResp) / rangeResp;
            double normCost = (rCost - minCost) / rangeCost;

            // Score Pesato (più basso è meglio)
            double score = (w * normTime) + ((1 - w) * normCost);

            if (score < bestScore) {
                bestScore = score;
                bestConfigData = row;
            }
        }

        // --- OUTPUT ---
        if (bestConfigData != null) {
            System.out.println("\n=== CONFIGURAZIONE VINCITRICE (Score: " + String.format("%.4f", bestScore) + ") ===");
            System.out.printf("Parametri S: [%d, %d, %d, %d, %d]%n",
                    (int)bestConfigData[0], (int)bestConfigData[1], (int)bestConfigData[2], (int)bestConfigData[3], (int)bestConfigData[4]);
            System.out.printf("Tempo Risposta: %.2f (Range rilevato: %.2f - %.2f)%n", bestConfigData[5], minResp, maxResp);
            System.out.printf("Costo Totale: %.2f (Range rilevato: %.2f - %.2f)%n", bestConfigData[6], minCost, maxCost);
        } else {
            System.out.println("Nessuna configurazione trovata.");
        }
    }

    private void initCenters(int[] config) {
        meanServiceTimes = new double[] {
                0.00823,  // cassa 1  (media ~121s, +50% rispetto a 81s)
                0.00673,  // cassa 2  (media ~149s, +50% rispetto a 99s)
                0.00741,  // cassa 3  (media ~135s, +50% rispetto a 90s)
                0.00617,  // cassa 4  (media ~162s, +50% rispetto a 108s)
                0.00926   // cassa 5  (media ~108s, +50% rispetto a 72s)
        };

        centers = new Center[7];
        casse = new Casse(config[0],v,meanServiceTimes);
        centers[0] = casse;
        cassaOnline = new CassaOnline(v);
        centers[1] = cassaOnline;
        dispatcher = new Dispatcher(v);
        centers[2] = dispatcher;
        braccioUno = new BraccioUno(v);
        centers[3] = braccioUno;
        braccioDue = new BraccioDue(v,inventorySystem);
        centers[4] = braccioDue;
        magazziniere = new Magazziniere(config[1],v);
        centers[5] = magazziniere;
        // Nuovo centro CassePagamento (2 serventi) a valle dei bracci per i clienti fisici.
        pagamento = new Pagamento(2, v);
        centers[6] = pagamento;
    }

    /** Seed master per run() e infinite-horizon; parametrizzabile (es. multi-seed di conferma in
     *  BatchCalibration). Default storico 123456789 → nessun cambiamento per gli altri chiamanti. */
    public long infiniteSeed = 123456789L;

    private void initGenerators() {
        r = new Rngs();                  //istanzia la libreria per la generazione dei valori randomici
        r.plantSeeds(infiniteSeed);      //inizializza seed da cui produce i seed dei diversi stream
        v = new Rvgs(r);                 //istanzia la libreria per la generazione delle variate aleatorie
        rvms = new Rvms();
    }

    public void initSeed(Rngs r, Rvgs v) {
        this.r = r;
        this.v = v;
        this.v.rngs = r;
    }

    public void setStop(double stopTime) {
        this.STOP = stopTime;
    }

    /**
     * Snapshot dei totali cumulativi della replica/run (NON azzerati per-finestra/batch), per i
     * riepiloghi aggregati di orizzonte finito/infinito.
     * Indici: 0=abbandoni, 1=OOS articoli, 2=clienti serviti, 3=farmaci venduti, 4=farmaci caricati,
     * 5=farmaci generati, 6=costo inventario (€), 7=delivery lag medio (s),
     * 8=delivery lag MAX (s), 9=frazione ordini con lag > R (reviewSeconds).
     */
    public double[] getRunSummary() {
        double[] s = new double[10];
        if (globalStatistics != null) {
            s[0] = globalStatistics.totalRenegingCum;
            s[1] = globalStatistics.totalOosCum;
            s[2] = globalStatistics.totalExitsCum;
            s[3] = globalStatistics.farmaciVendutiCum;
            s[4] = globalStatistics.farmaciCaricatiCum;
            s[5] = globalStatistics.generatiCum;
        }
        s[6] = getInventoryTotalCost();
        s[7] = (inventorySystem != null) ? inventorySystem.getAverageDeliveryLag() : 0.0;
        s[8] = (inventorySystem != null) ? inventorySystem.getMaxDeliveryLag() : 0.0;
        s[9] = (inventorySystem != null) ? inventorySystem.getFractionLagOver(reviewSeconds) : 0.0;
        return s;
    }

    public void setNumSampling(int numSampling) {
        this.numSampling = numSampling;
    }

    public void writeInventoryLevelSeries(String dir) {
        if (inventorySystem != null) inventorySystem.writeLevelTimeSeries(dir);
    }

    /** Se false, sopprime i print [REPLICA] e [PRIMA ORA] dentro runFiniteHorizonSimulation.
     *  Usato da InventoryConfigSearch per non inondare l'output durante la ricerca. */
    public boolean verboseOutput = true;

    /** Costo totale dell'inventory system (holding + shortage + ordering) al termine della run. */
    public double getInventoryTotalCost() {
        return (inventorySystem != null) ? inventorySystem.getTotalSystemCost() : 0.0;
    }

    public double getAvgGlobalResponseTime() {
        if (listGlobalResponse == null || listGlobalResponse.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : listGlobalResponse) sum += v;
        return sum / listGlobalResponse.size();
    }

    public double getAvgUtilizationCasse() {
        if (casse == null) return 0.0;
        double sum = 0.0; int cnt = 0;
        for (int s = 0; s < casse.servers.size(); s++) {
            if (casse.servers.get(s).served > 0) {
                sum += casse.getUtilization(s); cnt++;
            }
        }
        return cnt > 0 ? sum / cnt : 0.0;
    }

    public double getAvgJobsInSystem() {
        if (centers == null || t == null || t.current <= 0) return 0.0;
        double total = 0.0;
        for (int c = 0; c < 5; c++) {
            if (centers[c] != null && centers[c].area != null && centers[c].area.length > 0)
                total += centers[c].area[0].node / t.current;
        }
        return total;
    }

    private void initEvents() {
        events = new PriorityQueue<>(new Comparator<Event>() {
            @Override
            public int compare(Event e1, Event e2) {
                int timeComparison = Double.compare(e1.getTime(), e2.getTime());
                if (timeComparison == 0) {
                    return e1.getType().compareTo(e2.getType());
                }
                return timeComparison;
            }
        });
        // Metadati ordine: ripuliti a ogni (ri)avvio di simulazione (replica/batch/run).
        if (orderClassi != null) orderClassi.clear();
        if (arrivalTimePagamento != null) arrivalTimePagamento.clear();
        if (listPagamentoResponse != null) listPagamentoResponse.clear();
        if (listSystemResponse != null) listSystemResponse.clear();
    }

    private void initInventorySystem(int s1,int s2,int s3, int s4, int s5) {
        List<InventoryConfig> configs = new ArrayList<>();

        // Fattore di scala dell'INTERO inventario (S, s, livelli iniziali, capacità totale) per le
        // prove di robustezza: -Dinv.scale=1.0 = config attuale (ΣS=300). Scalando TUTTO dello stesso
        // fattore la "forma" della politica resta identica (stessi rapporti s/S) ma cresce il buffer
        // → cresce il margine (margine ∝ S a domanda fissa). Default 1.0 (nessun impatto).
        double f = Double.parseDouble(System.getProperty("inv.scale", "1.0"));
        // Scala SOLO le soglie di riordino s (S fisse, come da §3): -Dinv.s.scale<1 = riordini più
        // tardi → meno holding ma più OOS. È la leva di ottimizzazione (curva costo-vs-soglia). 1.0 = baseline.
        double fs = Double.parseDouble(System.getProperty("inv.s.scale", "1.0"));
        // Alternativa più difendibile per la BASELINE: esprimere s come FRAZIONE DIRETTA di S
        // ("riordino quando lo scaffale scende al X% della capacità", euristica da manager senza
        // analisi del lead time). Se -Dinv.sFracS>0 sovrascrive sia ss che s.scale. Default 0 = off.
        double sFracS = Double.parseDouble(System.getProperty("inv.sFracS", "0"));
        int[]  S    = { 80, 70, 60, 55, 35 };
        int[]  init = { 70, 65, 40, 40, 30 };
        int[]  ss   = { s1, s2, s3, s4, s5 };
        // Costi per classe coerenti con l'economia di una farmacia (valore-assunzione, ritoccabili):
        //   - valore unitario all'ingrosso (€): C1 ricetta 22, C2 OTC 6, C3 integratori 10,
        //     C4 dispositivi 15, C5 galeniche 20;
        //   - holding h = valore × 25%/anno ÷ 365 = €/unità/GIORNO (capitale+stoccaggio+scadenza);
        //   - shortage p = margine perso per vendita mancata ≈ valore × 40% (lost-sales);
        //   - order c = €2.5 per riordino interno (handling amministrativo).
        // NB: il rapporto p/h resta ~500 (shortage una-tantum vs holding giornaliero) → lo shortage
        // domina, com'è strutturalmente corretto in inventory theory (§19), indipendente dai valori.
        double[] h  = { 0.0151, 0.0041, 0.0068, 0.0103, 0.0137 };  // €/unità/giorno
        double[] p  = { 8.8,    2.4,    4.0,    6.0,    8.0    };  // €/unità persa
        double    c =   2.5;                                       // €/ordine
        for (int i = 0; i < 5; i++) {
            int Si  = (int) Math.round(S[i]    * f);
            int si  = (sFracS > 0)
                    ? (int) Math.round(sFracS * Si)        // s = X% della capacità S (baseline euristica)
                    : (int) Math.round(ss[i] * f * fs);    // s ottimizzato (eventualmente scalato)
            // Livello iniziale: se initLevelFraction>0 → f_init·S (apertura banco), altrimenti default.
            int ini = (initLevelFraction > 0)
                    ? (int) Math.round(initLevelFraction * Si)
                    : (int) Math.round(init[i] * f);
            if (si >= Si) si = Si - 1;   // garantisce s < S anche dopo l'arrotondamento
            if (ini > Si) ini = Si;      // il livello iniziale non può superare la capacità
            configs.add(new InventoryConfig(i + 1, si, Si, ini, h[i], p[i], c));
        }
        int totalCap = (int) Math.round(300 * f);

        inventorySystem = new InventorySystem();
        inventorySystem.initializeInventories(configs, totalCap);
        // Distribuzione di estrazione classe ∝ capacità S (bilancia la domanda fra le classi).
        classCdf = inventorySystem.getCapacityCdf();
    }

    private Center getEventUser() {
        r.selectStream(StreamType.STREAM_ROUTING_CASSE);
        double random = r.random();
        if (random <= 0.95) {
            arrivalsCasse++;
            return casse;                //pA --> utente abbonato che si dirige ai tornelli
        }
        else {
            arrivalsCassaOnline++;
            return cassaOnline;                    //pF --> utente si dirige verso biglietteria fisica
        }
    }

    public void setArrivalRate(double rate) {
        this.arrivalRate = rate;
    }

    private static int nextId = 1;  // contatore ID globale
    public static void resetNextId() { nextId = 1; }

    private String generaClasseFarmaco() {
        return SimulationValues.getNameFromClasseId(generaClasseIdFarmaco());
    }

    /**
     * Estrae la classe (id 1..n) di UN farmaco richiesto con probabilità ∝ capacità S della classe
     * (CDF in classCdf, da InventorySystem.getCapacityCdf). Bilancia la domanda fra le classi
     * (ognuna proporzionale a quanto può rifornire) → meno OOS da squilibrio. Resta un processo a
     * probabilità FISSE (non stato-dipendente). Fallback al vecchio mix 35/25/15/15/10 se la CDF
     * non è inizializzata.
     */
    private int generaClasseIdFarmaco() {
        r.selectStream(StreamType.STREAM_CLASS_PHARMA);
        double u = v.uniform(0.0, 1.0);

        if (classCdf != null && classCdf.length > 0) {
            for (int i = 0; i < classCdf.length; i++) {
                if (u < classCdf[i]) return i + 1;
            }
            return classCdf.length;
        }

        // Fallback (CDF non inizializzata): vecchio mix fisso 35/25/15/15/10.
        if (u < 0.35) return 1;
        else if (u < 0.60) return 2;
        else if (u < 0.75) return 3;
        else if (u < 0.90) return 4;
        else return 5;
    }

    private void generateTimeChange() {
        // ⚠️ CORREZIONE: Genera solo il PRIMO evento di cambio fascia
        // Gli altri verranno generati dinamicamente nel handleTimeChange
        int firstChange = 1800; // 8:30 (prima variazione di fascia)
        events.add(new Event(EventType.TIMECHANGE, firstChange));

        //System.out.println("Pianificato primo TIMECHANGE alle ore " + (firstChange/3600.0));
    }

    private void handleTimeChange(Event event, double Stop) {
        double currentTime = event.getTime();

        if (currentTime>1800 ){
            for(int i = 0 ; i < casse.serverStatus.length; i++){
                if (listBloccati.contains(casse.occupant[i])){
                    if (casse.occupant[i] != -1 && casse.serverStatus[i]!= -1) {
                        casse.markServerIdle(i, casse.occupant[i], "standard", "forzato", t.current);
                    }
                }
            }
        }

        listBloccati.removeAll(listBloccati);
        for(int i =0 ; i < casse.serverStatus.length; i++){
            listBloccati.add(casse.occupant[i]);
        }
        if (Stop==STOP) {
            // Calcolo dell'indice di fascia (ogni fascia lunga 1800 secondi)
            int fasciaIndex = (int) (currentTime / 1800);

            // Safety check e aggiornamento arrival rate
            if (fasciaIndex >= arrivalRates.length) {
                arrivalRate = arrivalRates[arrivalRates.length - 1];
                //System.out.println("Fine fasce orarie - mantengo ultimo rate: " + arrivalRate);
            } else {
                arrivalRate = arrivalRates[fasciaIndex];
                //System.out.printf("Nuovo arrival rate: %.6f clienti/secondo%n", arrivalRate);
            }
        }
        if (currentTime != 43200 && (currentTime % reviewSeconds == 0)) {
            // Revisione scorte (s,S) OGNI ORA (reviewSeconds): il carico generato qui ha
            // l'intera ora per essere completato → non sfora nell'ora successiva (delivery lag
            // ~30 min < 60 min). I TIMECHANGE restano a 30 min (per λ(t)), ma il riordino è orario.
            List<Order> newOrders = inventorySystem.checkAndCreateOrders();
            for (int i = 0; i < newOrders.size(); i++) {
                dispatchReorderToMagazziniere(newOrders.get(i)); // spezza in ordini ≤ 20 unità
            }
        }
        // ⚠️ AGGIUNTA CRITICA: Pianifica il PROSSIMO evento TIMECHANGE
        double nextChangeTime = currentTime + 1800; // +30 minuti
        double endTime = 41400; // 19:30 (ultima variazione)
        if (nextChangeTime <= Stop) {
            Event nextTimeChange = new Event(EventType.TIMECHANGE, nextChangeTime);
            events.add(nextTimeChange);
            // System.out.printf("  → Pianificato prossimo TIMECHANGE alle ore %.2f%n", nextChangeTime/3600);
        } else {
            //System.out.println("  → Nessun altro TIMECHANGE da pianificare");
        }


    }

    private void generateAllExternalArrivals() {
        double currentTime = 0.0;
        int arrivalCount = 0;
        notCompleted = new ArrayList<>();
        //System.out.println("Pre-generando arrivi esterni per fascia...");

        int fasciaIndex = 0;
        r.selectStream(StreamType.STREAM_ARRIVAL);
        while (currentTime < STOP && fasciaIndex < arrivalRates.length) {
            double endFascia = (fasciaIndex + 1) * 1800;
            double lambda = arrivalRates[fasciaIndex];

            while (currentTime < endFascia && currentTime < STOP && arrivalCount < 10000) {
                currentTime += v.exponential(1.0 / lambda); //lambda for 0 abandon 0.004035
                if (currentTime <= STOP) {
                    Event arrival = createArrivalEventAtTime(currentTime);
                    events.add(arrival);
                    // registerGeneratedJob() is called when the ARRIVAL event actually fires
                    // in each simulation loop (run / runFiniteHorizonSimulation), so that
                    // per-window counts are correct after resetBatchStatistics() resets the counter.
                    arrivalCount++;
                }
            }

          //  System.out.printf("  → Fascia %d: fino alle %.2f, λ = %.6f%n", fasciaIndex, endFascia / 3600.0, lambda);
            fasciaIndex++;
        }

        //System.out.printf("Pre-generati %d arrivi esterni fino al tempo %.1f%n", arrivalCount, STOP);
    }

    private Event createArrivalEventAtTime(double arrivalTime) {
        r.selectStream(StreamType.STREAM_N_PHARMA);
        Event arrival = new Event(EventType.ARRIVAL, arrivalTime);

        Center destinazione = getEventUser();
        arrival.setCenter(destinazione);
        arrival.setOnline(destinazione == cassaOnline);
        arrival.setExternal(true);
        arrival.setId(nextId++);
        int numFarmaci = (int) Math.max(1, Math.round(v.exponential(2.71)));
        arrival.setNumeroFarmaciRichiesti(numFarmaci);
        // OPZIONE (b): il cliente puo' richiedere farmaci di CLASSI DIVERSE nello stesso ordine.
        // Estraggo una classe per ciascuno dei K farmaci e le memorizzo nella mappa orderClassi
        // (consumate poi per-articolo al braccio). classeFarmaco resta = classe del 1° farmaco
        // (solo per display / registrazione abbandoni).
        int[] classi = new int[numFarmaci];
        for (int i = 0; i < numFarmaci; i++) {
            classi[i] = generaClasseIdFarmaco();
        }
        orderClassi.put(arrival.getId(), classi);
        arrival.setClasseFarmaco(SimulationValues.getNameFromClasseId(classi[0]));
        arrival.setMittente("customer");
        arrival.setOriginalCassaId(destinazione.ID);

        arrival.setFirstArrivalTime(arrivalTime);

        total += arrival.getNumeroFarmaciRichiesti();

        // RIMOSSO: arrivalTimeById[arrival.getId()] = arrivalTime;  <-- CAUSAVA L'ERRORE

        notCompleted.add(arrival.getId());
        globalStatistics.registerFarmaciRichiesti(numFarmaci);
        return arrival;
    }

    private void generateArrivalNextCenter(Event currentEvent) {
        Center currentCenter = currentEvent.getCenter();
        int nextCenterID = currentCenter.getNextCenter();

        if (nextCenterID == -1) {
            // Caso speciale - torna alla cassa di origine
            Event serviceEvent = null;
            if (currentCenter instanceof PriorityQueueCenter) {
                serviceEvent = ((PriorityQueueCenter) currentCenter).getCurrentServiceEvent();
            } else if (currentCenter instanceof BraccioDue) {
                serviceEvent = ((BraccioDue) currentCenter).getCurrentServiceEvent();
            }

            Event sourceEvent = serviceEvent != null ? serviceEvent : currentEvent;
            int originalCassaId = sourceEvent.getOriginalCassaId();
        }
        else {
            Center nextCenter = centers[nextCenterID - 1];
            if (nextCenter == null) {
                throw new IllegalStateException("Centro successivo non inizializzato");
            }

            Event arrivalNextCenter = new Event(EventType.ARRIVAL, currentEvent.getTime());
            arrivalNextCenter.setCenter(nextCenter);
            arrivalNextCenter.setExternal(false);

            Event serviceEvent = null;

            // ✅ FIX: Per le casse fisiche, usa SEMPRE currentEvent
            if (currentCenter instanceof Casse) {
                // ✅ CASSE FISICHE: currentEvent ha già tutte le info corrette
                serviceEvent = null; // Forza uso di currentEvent
            } else if (currentCenter instanceof CassaOnline) {
                serviceEvent = ((CassaOnline) currentCenter).getCurrentServiceEvent();
            } else if (currentCenter instanceof Dispatcher) {
                serviceEvent = ((Dispatcher) currentCenter).getCurrentServiceEvent();
            } else if (currentCenter instanceof PriorityQueueCenter) {
                serviceEvent = ((PriorityQueueCenter) currentCenter).getCurrentServiceEvent();
            }

            // ✅ Per le casse fisiche, sourceEvent sarà sempre currentEvent
            Event sourceEvent = serviceEvent != null ? serviceEvent : currentEvent;

            arrivalNextCenter.setClasseFarmaco(sourceEvent.getClasseFarmaco());
            arrivalNextCenter.setNumeroFarmaciRichiesti(sourceEvent.getNumeroFarmaciRichiesti());
            arrivalNextCenter.setOnline(sourceEvent.checkOnline());
            arrivalNextCenter.setId(sourceEvent.getId());
            arrivalNextCenter.setOriginalCassaId(sourceEvent.getOriginalCassaId());

            String mittente;
            if (currentCenter instanceof Casse || currentCenter instanceof CassaOnline) {
                mittente = "cassa";
            } else if (currentCenter instanceof Dispatcher) {
                mittente = "dispatcher";
            } else if (currentCenter instanceof Magazziniere) {
                mittente = "magazziniere";
            } else {
                mittente = sourceEvent.getMittente();
            }

            arrivalNextCenter.setMittente(mittente);

            events.add(arrivalNextCenter);
        }
    }

    private Event generateDepartureEvent(Center center, Server server) {
        Event current = center.getCurrentServiceEvent();  // <-- Serve il job in servizio
        if (current == null) {
            System.err.println("[FATAL] generateDepartureEvent: currentServiceEvent null per centro " + center.name);
            return null;
        }
        if (server == null) {
            System.err.printf("[ERRORE] Server nullo per DEPARTURE su centro %s%n", center.name);
            return null;
        }


        double endTime = t.current + center.lastService;
        Event departure = new Event(EventType.DEPARTURE, endTime);

        departure.setId(current.getId());
        departure.setCenter(center);
        departure.setServer(server);
        departure.setClasseFarmaco(current.getClasseFarmaco());
        departure.setNumeroFarmaciRichiesti(current.getNumeroFarmaciRichiesti());
        departure.setOnline(current.checkOnline());
        departure.setExternal(current.isExternal());
        departure.setMittente(current.getMittente());
        departure.setOriginalCassaId(current.getOriginalCassaId());
        departure.setFirstArrivalTime(current.getFirstArrivalTime());

        // Carico INCREMENTALE: se è un rifornimento al Braccio Uno appena entrato in servizio,
        // deposita le Q unità a banco una alla volta lungo [t.current, endTime] (micro-eventi RESTOCK)
        // invece che in blocco al departure. endTime/servizio invariati → statistiche centro identiche.
        scheduleIncrementalRestock(center, current, t.current, center.lastService);

        return departure;
    }

    private void generateSamplingEvents() {
        int startTime = 450;        // Primo campione alle 8:07:30
        int endTime = 43200;        // Ultimo campione alle 20:00
        int interval = 450;         // 7,5 minuti = 450 secondi

        for (int t = startTime; t <= endTime; t += interval) {
            events.add(new Event(EventType.SAMPLING, t));
        }
    }

    private void handleAbandon(Event event) {
        Center center = event.getCenter();
        if (!(center instanceof Casse)) return;  // sicurezza: solo clienti fisici

        int id = event.getId();

        // Inizializza se null (safety check)
        if (fakeAbandon == null) {
            fakeAbandon = new ArrayList<>();
        }

        if (fakeAbandon.contains(id)) {
            // Job è già in servizio, ignora l'evento di abbandono
            System.out.printf("[T=%.1f(%02d:%02dh)] JOB_%d: ABBANDONO IGNORATO (job già in servizio)%n",
                    t.current, 8 + (int)(t.current / 60) / 60, (int)(t.current / 60) % 60, id);

            // ⚠️ RIMUOVI dalla lista per evitare memory leak
            fakeAbandon.remove(Integer.valueOf(id));
        }
        else {
            // Abbandono reale dalla coda
            String classeFarmaco = event.getClasseFarmaco();
            int numero =event.getNumeroFarmaciRichiesti();
            // Se non presente nell'evento, cerca nella coda (fallback)
            if (classeFarmaco == null) {
                classeFarmaco = ((Casse) center).getClasseFarmacoById(id);
            }

            // Tenta la rimozione del job dalla coda
            boolean removed = ((Casse) center).removeJobById(id,t.current);

            if (removed) {
                leavingJob++;
                Double nextJobArrivalTime = casse.arrivalTimes.get(id);
                if (nextJobArrivalTime != null) {
                    double waitingTime = t.current - nextJobArrivalTime;
                    casse.waitingTimes.add(waitingTime);
                    //System.out.printf("[T=%.1f(%02d:%02dh)] JOB_%d: ABANDON from center: %s | Ha aspettato %.1f%n",t.current,8 + (int)(t.current / 60) / 60, (int)(t.current / 60) % 60,event.getId(), center.name ,waitingTime );

                }

                farmaciPersi+= numero;
                notCompleted.remove(Integer.valueOf(id));
                //System.out.printf("[T=%.1f(%02d:%02dh)] JOB_%d: ABANDON from center: %s | Ha aspettato %.1f%n",t.current,8 + (int)(t.current / 60) / 60, (int)(t.current / 60) % 60,event.getId(), center.name ,(t.current - arrivalTimeById[event.getId()]) );


                if (globalStatistics != null && classeFarmaco != null) {
                    globalStatistics.registerAbandon(classeFarmaco, t.current);
                }
            } else {
//                System.out.printf("[T=%.1f(%02d:%02dh)] JOB_%d: Job non trovato in coda per abbandono%n",
//                        t.current, 8 + (int)(t.current / 60) / 60, (int)(t.current / 60) % 60, id);
            }
        }
    }

    private void handleCassaOnline(Event event, Center currentCenter, int departureResult) {
        globalStatistics.registerCassaOnlineCompletion();

        if (currentCenter.getNextCenter() != 0) {
            generateArrivalNextCenter(event);
        }

        ((CassaOnline) cassaOnline).resetCurrentServiceEvent();

        // Il server è già libero (processDeparture ha fatto numJobs--, serverBusy=false).
        // Avviamo subito il prossimo cliente in coda, così:
        //   - il waiting time è misurato correttamente (t.current = DEPARTURE del precedente)
        //   - non c'è gap artificioso tra DEPARTURE e il momento in cui il prossimo inizia
        // NON richiamare handleCompletion per questo: lì avviene un doppio decremento di numJobs.
        if (!cassaOnline.waitingQueue.isEmpty() && !cassaOnline.serverBusy) {
            Event nextCustomer = cassaOnline.waitingQueue.poll();
            cassaOnline.contaJobCoda--;
            cassaOnline.currentServiceEvent = nextCustomer;
            cassaOnline.serverBusy = true;
            cassaOnline.numBusyServers = 1;

            // Waiting time corretto: arrivo → inizio servizio (= t.current)
            if (cassaOnline.arrivalTimes.containsKey(nextCustomer.getId())) {
                double waiting = t.current - cassaOnline.arrivalTimes.get(nextCustomer.getId());
                cassaOnline.waitingTimes.add(waiting);
            }

            // Genera departure per il prossimo cliente
            double serviceTime = cassaOnline.getService(0);
            Event dep = new Event(EventType.DEPARTURE, t.current + serviceTime);
            dep.setId(nextCustomer.getId());
            dep.setCenter(cassaOnline);
            dep.setServer(cassaOnline.servers.get(0));
            dep.setClasseFarmaco(nextCustomer.getClasseFarmaco());
            dep.setNumeroFarmaciRichiesti(nextCustomer.getNumeroFarmaciRichiesti());
            dep.setOnline(true);
            dep.setExternal(nextCustomer.isExternal());
            dep.setMittente(nextCustomer.getMittente());
            dep.setOriginalCassaId(nextCustomer.getOriginalCassaId());
            dep.setFirstArrivalTime(nextCustomer.getFirstArrivalTime());
            events.add(dep);
        } else if (cassaOnline.waitingQueue.isEmpty()) {
            // Nessuno in coda: server davvero idle
            cassaOnline.numBusyServers = 0;
        }
    }

    private void handleCasse(Event event, Center currentCenter, int departureResult) {
        globalStatistics.registerCassaCompletion();

        int jobId = event.getId();
        int serverIndex = event.getServer().id;

        // 1. LIBERA la cassa al PROPRIO departure (fine pagamento). Nel modello precedente la
        //    cassa restava occupata fino al COMPLETION (= fine fetch del robot) → utilizzazione
        //    gonfiata. Ora il servizio cassa = solo il pagamento → util ≈ λ·E[S_cassa].
        casse.markServerIdle(serverIndex, jobId, "standard", "departure cassa", t.current);

        // 2. Instrada l'ORDINE INTERO (un solo job, porta con sé K = numeroFarmaciRichiesti) al
        //    Dispatcher. Niente spacchettamento qui.
        if (currentCenter.getNextCenter() != 0) {
            generateArrivalNextCenter(event);
        }

        // 3. Se il server appena liberato ha clienti in coda, avvia il prossimo con un servizio
        //    cassa completo (al suo DEPARTURE seguirà di nuovo questo handler).
        if (casse.serverStatus[serverIndex] == 0 && !casse.waitingQueue.isEmpty()) {
            Event nextCustomer = casse.waitingQueue.poll();
            Server specificServer = casse.servers.get(serverIndex);
            casse.contaJobCoda--;

            if (casse.arrivalTimes.containsKey(nextCustomer.getId())) {
                double waiting = t.current - casse.arrivalTimes.get(nextCustomer.getId());
                casse.waitingTimes.add(waiting);
            }

            casse.serviceEvents.put(specificServer, nextCustomer);
            casse.markServerBusy(nextCustomer, serverIndex, nextCustomer.getId(), "extract from completion queue", t.current);
            specificServer.idle = false;
            specificServer.served++;

            double serviceTime = casse.getService(serverIndex);
            casse.lastService = serviceTime;
            specificServer.service += serviceTime;

            Event dep = new Event(EventType.DEPARTURE, t.current + serviceTime);
            dep.setId(nextCustomer.getId());
            dep.setCenter(casse);
            dep.setServer(specificServer);
            dep.setClasseFarmaco(nextCustomer.getClasseFarmaco());
            dep.setNumeroFarmaciRichiesti(nextCustomer.getNumeroFarmaciRichiesti());
            dep.setOnline(nextCustomer.checkOnline());
            dep.setExternal(nextCustomer.isExternal());
            dep.setMittente(nextCustomer.getMittente());
            dep.setOriginalCassaId(serverIndex);
            dep.setFirstArrivalTime(nextCustomer.getFirstArrivalTime());
            events.add(dep);
        }
    }

    private void handleMagazziniere(Event event, Center currentCenter, int departureResult) {
        globalStatistics.registerMagazziniereCompletion();

        int jobId = event.getId();
        int serverIndex = event.getServer().id;

        // 1. Invia l'ORDINE DI RIFORNIMENTO come UN solo job alla coda ad ALTA priorità del
        //    Braccio Uno, con numeroFarmaciRichiesti = Q. Il servizio del braccio sarà la somma
        //    di Q prelievi; al termine handleBraccioUno fa receiveDelivery(Q). Niente fork.
        Event arrivalBraccioUno = new Event(EventType.ARRIVAL, t.current);
        arrivalBraccioUno.setCenter(braccioUno);
        arrivalBraccioUno.setMittente("magazziniere");
        arrivalBraccioUno.setClasseFarmaco(event.getClasseFarmaco());
        arrivalBraccioUno.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
        arrivalBraccioUno.setExternal(false);
        arrivalBraccioUno.setOnline(event.checkOnline());
        arrivalBraccioUno.setId(jobId);
        arrivalBraccioUno.setFirstArrivalTime(event.getFirstArrivalTime());
        arrivalBraccioUno.setOriginalCassaId(serverIndex);
        events.add(arrivalBraccioUno);

        // 2. LIBERA il magazziniere al PROPRIO servizio (non resta bloccato fino al carico robot).
        magazziniere.markServerIdle(serverIndex, jobId, "standard", "departure magazziniere", t.current);

        // 3. Drena la coda del magazziniere sul server appena liberato.
        if (magazziniere.serverStatus[serverIndex] == 0 && !magazziniere.waitingQueue.isEmpty()) {
            Event nextOrder = magazziniere.waitingQueue.poll();
            Server specificServer = magazziniere.servers.get(serverIndex);
            magazziniere.contaJobCoda--;

            if (magazziniere.arrivalTimes.containsKey(nextOrder.getId())) {
                double waiting = t.current - magazziniere.arrivalTimes.get(nextOrder.getId());
                magazziniere.waitingTimes.add(waiting);
            }

            magazziniere.serviceEvents.put(specificServer, nextOrder);
            magazziniere.markServerBusy(nextOrder, serverIndex, nextOrder.getId(), "extract from completion queue", t.current);
            specificServer.idle = false;
            specificServer.served++;

            // Q del prossimo ordine (currentEvent qui è ancora l'ordine appena partito) → uso il
            // metodo esplicito per quantità.
            double serviceTime = magazziniere.serviceForQuantity(nextOrder.getNumeroFarmaciRichiesti());
            magazziniere.lastService = serviceTime;
            specificServer.service += serviceTime;

            Event dep = new Event(EventType.DEPARTURE, t.current + serviceTime);
            dep.setId(nextOrder.getId());
            dep.setCenter(magazziniere);
            dep.setServer(specificServer);
            dep.setClasseFarmaco(nextOrder.getClasseFarmaco());
            dep.setNumeroFarmaciRichiesti(nextOrder.getNumeroFarmaciRichiesti());
            dep.setOnline(nextOrder.checkOnline());
            dep.setExternal(nextOrder.isExternal());
            dep.setMittente(nextOrder.getMittente());
            dep.setOriginalCassaId(serverIndex);
            dep.setFirstArrivalTime(nextOrder.getFirstArrivalTime());
            events.add(dep);
        }
    }

    private void handleDispatcher(Event event, Center currentCenter, int departureResult) {
        globalStatistics.registerDispatcherCompletion();
        Event serviceEvent = event;  // L'evento che ha appena completato il dispatcher

        String classe = serviceEvent.getClasseFarmaco();
        int quantita = serviceEvent.getNumeroFarmaciRichiesti();   // K = numero farmaci dell'ordine
        int parentId = serviceEvent.getId();

        // Routing applicato UNA SOLA VOLTA per l'INTERO ordine (non più per frammento).
        boolean toBraccioUno;
        if (ROUTE_MODE.equals("fixed")) {
            // Scheduling astratto a probabilita' fissa, con finestra protetta schedulata.
            boolean protectedNow = ROUTE_WINDOW
                    && ((t.current % INVENTORY_SLOT_SECONDS) < PROTECTED_WINDOW_SECONDS);
            if (protectedNow) {
                toBraccioUno = false; // 0% a Braccio Uno nei primi 10 min del ciclo
            } else {
                r.selectStream(StreamType.STREAM_ROUTING_BRACCI);
                toBraccioUno = (r.random() < ROUTE_PB1);
            }
        } else {
            // Overflow: a Braccio Uno solo se libero.
            toBraccioUno = (braccioUno.getHighPriorityQueueSize() == 0
                    && braccioUno.getLowPriorityQueueSize() == 0);
        }

        // UN solo arrivo per l'ordine intero: porta con sé K e la classe; il servizio del braccio
        // sarà la somma di K prelievi (servizio compound/phase-type).
        Event arrivalBraccio = new Event(EventType.ARRIVAL, t.current);
        arrivalBraccio.setCenter(toBraccioUno ? braccioUno : braccioDue);
        arrivalBraccio.setClasseFarmaco(classe);
        arrivalBraccio.setMittente("dispatcher");
        arrivalBraccio.setNumeroFarmaciRichiesti(quantita);
        arrivalBraccio.setExternal(false);
        arrivalBraccio.setOnline(serviceEvent.checkOnline());
        arrivalBraccio.setId(parentId);
        arrivalBraccio.setFirstArrivalTime(serviceEvent.getFirstArrivalTime());
        arrivalBraccio.setOriginalCassaId(serviceEvent.getOriginalCassaId());

        globalStatistics.registerSpacchettamento();   // ora = 1 ordine instradato (non K frammenti)
        events.add(arrivalBraccio);

        if (departureResult == 0) {
            events.add(generateDepartureEvent(currentCenter, event.getServer()));
        }
    }

    /**
     * Consuma l'inventario PER-ARTICOLO per un ordine cliente (prelievo): per ciascuno dei K
     * farmaci dell'ordine (possibili classi diverse, mappa orderClassi) tenta requestItem e conta
     * gli out-of-stock. L'ordine si completa comunque (fulfillment parziale). Registra le perdite
     * OOS per-articolo (registratore della coda corrispondente: braccio uno/due).
     */
    private void consumaInventarioOrdine(Event event, boolean braccioUnoCenter) {
        int[] classi = orderClassi.remove(event.getId());
        if (classi == null) return; // robustezza: ordine senza classi registrate
        for (int classId : classi) {
            boolean soddisfatto = inventorySystem.requestItem(classId);
            if (soddisfatto) {
                // Farmaco effettivamente prelevato e venduto (conteggio PER-ARTICOLO).
                globalStatistics.registerFarmaciVenduti(1);
            } else {
                String nome = SimulationValues.getNameFromClasseId(classId);
                globalStatistics.registerOutOfStock(nome, t.current);
                if (braccioUnoCenter) {
                    globalStatistics.registerOutOfStockBraccioUno(nome, t.current);
                } else {
                    globalStatistics.registerOutOfStockBraccioDue(nome, t.current);
                }
                listOutOfStock.add(event.getId());
            }
        }
    }

    /**
     * Uscita dell'ordine cliente dal braccio: i clienti FISICI proseguono verso CassePagamento
     * (nuovo ARRIVAL), gli ONLINE (già pagati) escono subito dal sistema (COMPLETION).
     */
    private void uscitaOrdineDaBraccio(Event event, Center braccioCenter) {
        if (event.checkOnline()) {
            // Online: già pagato → COMPLETION (uscita dal sistema)
            Event completion = new Event(EventType.COMPLETION, t.current);
            completion.setCenter(braccioCenter);
            completion.setId(event.getId());
            completion.setClasseFarmaco(event.getClasseFarmaco());
            completion.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
            completion.setOnline(true);
            completion.setMittente(event.getMittente());
            completion.setFirstArrivalTime(event.getFirstArrivalTime());
            events.add(completion);
        } else {
            // Fisico: → CassePagamento (paga ed esce lì)
            Event arrivalPagamento = new Event(EventType.ARRIVAL, t.current);
            arrivalPagamento.setCenter(pagamento);
            arrivalPagamento.setExternal(false);
            arrivalPagamento.setClasseFarmaco(event.getClasseFarmaco());
            arrivalPagamento.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
            arrivalPagamento.setOnline(false);
            arrivalPagamento.setId(event.getId());
            arrivalPagamento.setMittente("braccio");
            arrivalPagamento.setOriginalCassaId(event.getOriginalCassaId());
            arrivalPagamento.setFirstArrivalTime(event.getFirstArrivalTime());
            events.add(arrivalPagamento);
        }
    }

    /** Schedula il DEPARTURE del prossimo job già avviato in servizio (departureResult==0). */
    private void scheduleNextBraccioDeparture(Center currentCenter) {
        Event nextJob;
        if (currentCenter instanceof PriorityQueueCenter) {
            nextJob = ((PriorityQueueCenter) currentCenter).getCurrentServiceEvent();
        } else {
            nextJob = ((BraccioDue) currentCenter).getCurrentServiceEvent();
        }
        if (nextJob != null) {
            Event newDeparture = new Event(EventType.DEPARTURE, t.current + currentCenter.lastService);
            newDeparture.setId(nextJob.getId());
            newDeparture.setCenter(currentCenter);
            newDeparture.setServer(currentCenter.servers.get(0));
            newDeparture.setClasseFarmaco(nextJob.getClasseFarmaco());
            newDeparture.setNumeroFarmaciRichiesti(nextJob.getNumeroFarmaciRichiesti());
            newDeparture.setOnline(nextJob.checkOnline());
            newDeparture.setExternal(nextJob.isExternal());
            newDeparture.setMittente(nextJob.getMittente());
            newDeparture.setOriginalCassaId(nextJob.getOriginalCassaId());
            newDeparture.setFirstArrivalTime(nextJob.getFirstArrivalTime());
            events.add(newDeparture);
            // Carico incrementale anche quando il rifornimento parte in servizio drenando la coda.
            scheduleIncrementalRestock(currentCenter, nextJob, t.current, currentCenter.lastService);
        }
    }

    /**
     * Carico INCREMENTALE del rifornimento (Braccio Uno). Invece di depositare le Q unità a banco in
     * blocco al DEPARTURE, schedula Q micro-eventi RESTOCK distribuiti uniformemente sulla finestra di
     * servizio [tStart, tStart+svc] (uno ogni svc/Q secondi). Conseguenze:
     *   - il SERVIZIO del Braccio Uno resta un blocco unico: utilizzazione, tempo di servizio medio,
     *     code e istante di DEPARTURE NON cambiano (il deposito è solo lato inventario);
     *   - un prelievo cliente che arriva durante il carico trova le unità già caricate fino a quel
     *     momento → meno out-of-stock "di timing" (modella il robot che posa i farmaci uno alla volta).
     * Applicato SOLO ai rifornimenti (mittente=magazziniere) sul Braccio Uno; i prelievi cliente
     * restano atomici (consumati a fine servizio in handleBraccioUno/handleBraccioDue): tanto l'ordine
     * cliente esce dal sistema solo a frammenti completati.
     */
    private void scheduleIncrementalRestock(Center center, Event serviceJob, double tStart, double svc) {
        if (center != braccioUno || serviceJob == null) return;
        if (!"magazziniere".equals(serviceJob.getMittente())) return;
        int q = serviceJob.getNumeroFarmaciRichiesti();
        if (q <= 0 || svc <= 0) return;
        double step = svc / q;
        for (int i = 1; i <= q; i++) {
            Event restock = new Event(EventType.RESTOCK, tStart + i * step);
            restock.setId(serviceJob.getId());
            restock.setClasseFarmaco(serviceJob.getClasseFarmaco());
            restock.setNumeroFarmaciRichiesti(1);
            restock.setMittente("magazziniere");
            restock.setExternal(false);
            events.add(restock);
        }
    }

    private void handleBraccioUno(Event event, Center currentCenter, int departureResult, int departedJobId) {
        if (currentCenter.getNextCenter() != -1) return;

        boolean isReorder = "magazziniere".equals(event.getMittente());

        if (isReorder) {
            // RIFORNIMENTO (alta priorità): le Q unità sono GIÀ state depositate a banco in modo
            // INCREMENTALE durante il servizio, tramite i micro-eventi RESTOCK schedulati all'avvio
            // del servizio (scheduleIncrementalRestock). Qui resta solo la contabilità di completamento;
            // NON si fa più receiveDelivery in blocco (sarebbe un doppio deposito).
            globalStatistics.registerHighPriorityCompletion();
            globalStatistics.registerFarmaciCaricati(event.getNumeroFarmaciRichiesti()); // PER-ARTICOLO
        } else {
            // ORDINE CLIENTE (bassa priorità): consuma inventario per-articolo, poi prosegue.
            globalStatistics.registerLowPriorityCompletion();
            consumaInventarioOrdine(event, true);
        }

        globalStatistics.registerSuccessfulExitBracci();
        globalStatistics.registerBraccioUnoCompletion();

        // I rifornimenti non sono ordini cliente → non escono dal sistema. Gli ordini cliente
        // proseguono verso CassePagamento (fisici) o escono (online).
        if (!isReorder) {
            uscitaOrdineDaBraccio(event, currentCenter);
        }

        if (departureResult == 0) {
            scheduleNextBraccioDeparture(currentCenter);
        }
    }

    private void handleBraccioDue(Event event, Center currentCenter, int departureResult, int departedJobId) {
        if (currentCenter.getNextCenter() != -1) return;

        // Braccio Due serve SOLO ordini cliente (nessun rifornimento).
        globalStatistics.registerSuccessfulExitBracci();
        globalStatistics.registerBraccioDueCompletion();

        consumaInventarioOrdine(event, false);
        uscitaOrdineDaBraccio(event, currentCenter);

        if (departureResult == 0) {
            scheduleNextBraccioDeparture(currentCenter);
        }
    }

    /**
     * CassePagamento: il cliente fisico paga ed ESCE. Centro non bloccante (liberato al proprio
     * departure), coda drenata avviando il prossimo cliente. L'uscita dal sistema (COMPLETION)
     * registra il response time end-to-end.
     */
    private void handlePagamento(Event event, Center currentCenter, int departureResult) {
        int jobId = event.getId();
        int serverIndex = event.getServer().id;

        // Libera il servente al proprio departure
        pagamento.markServerIdle(serverIndex, jobId, "standard", "departure pagamento", t.current);

        // Uscita dal sistema
        Event completion = new Event(EventType.COMPLETION, t.current);
        completion.setCenter(currentCenter);
        completion.setId(jobId);
        completion.setClasseFarmaco(event.getClasseFarmaco());
        completion.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
        completion.setOnline(false);
        completion.setMittente(event.getMittente());
        completion.setFirstArrivalTime(event.getFirstArrivalTime());
        events.add(completion);

        // Drena la coda sul servente appena liberato
        if (pagamento.serverStatus[serverIndex] == 0 && !pagamento.waitingQueue.isEmpty()) {
            Event nextCustomer = pagamento.waitingQueue.poll();
            Server specificServer = pagamento.servers.get(serverIndex);
            pagamento.contaJobCoda--;

            if (pagamento.arrivalTimes.containsKey(nextCustomer.getId())) {
                double waiting = t.current - pagamento.arrivalTimes.get(nextCustomer.getId());
                pagamento.waitingTimes.add(waiting);
            }

            pagamento.serviceEvents.put(specificServer, nextCustomer);
            pagamento.markServerBusy(nextCustomer, serverIndex, nextCustomer.getId(), "extract from completion queue", t.current);
            specificServer.idle = false;
            specificServer.served++;

            double serviceTime = pagamento.getService(serverIndex);
            pagamento.lastService = serviceTime;
            specificServer.service += serviceTime;

            Event dep = new Event(EventType.DEPARTURE, t.current + serviceTime);
            dep.setId(nextCustomer.getId());
            dep.setCenter(pagamento);
            dep.setServer(specificServer);
            dep.setClasseFarmaco(nextCustomer.getClasseFarmaco());
            dep.setNumeroFarmaciRichiesti(nextCustomer.getNumeroFarmaciRichiesti());
            dep.setOnline(false);
            dep.setExternal(nextCustomer.isExternal());
            dep.setMittente(nextCustomer.getMittente());
            dep.setOriginalCassaId(nextCustomer.getOriginalCassaId());
            dep.setFirstArrivalTime(nextCustomer.getFirstArrivalTime());
            events.add(dep);
        }
    }

    void handleCompletion(Event event) {
        // [REFACTOR phase-type/ordine] Nel nuovo modello l'ordine cliente è UN solo job e si
        // completa quando ESCE dal braccio. La COMPLETION serve SOLO a registrare l'uscita dal
        // sistema e il response time end-to-end: NESSUN markServerIdle (cassa e magazziniere sono
        // già stati liberati al loro PROPRIO departure → niente più blocco). I rifornimenti del
        // magazziniere non generano più COMPLETION (gestiti interamente in handleBraccioUno).
        int jobId = event.getId();

        // Rimuovi eventuali ABANDON pendenti per questo ordine (è ormai servito).
        events.removeIf(e -> e.getType() == EventType.ABANDON && e.getId() == jobId);
        if (fakeAbandon != null) {
            fakeAbandon.remove(Integer.valueOf(jobId));
        }

        if (notCompleted.contains(jobId)) {
            notCompleted.remove(Integer.valueOf(jobId));
        }

        // Response time end-to-end FISICI (cassa fisica → uscita CassePagamento). Resta separato
        // perché riusato altrove (finito) come proxy del W dei clienti fisici.
        if (!event.checkOnline() && arrivalTimeCasse.containsKey(jobId)) {
            listGlobalResponse.add(t.current - arrivalTimeCasse.get(jobId));
        }

        // METODO B (timestamp diretto, TUTTI i clienti): uscita − ingresso, dove l'ingresso è
        // l'arrivo a cassa fisica (fisici) o cassa online (online). È il tempo realmente passato
        // nel sistema dal singolo cliente, da confrontare con la somma dei W per-centro (metodo A).
        Double ingresso = arrivalTimeCasse.containsKey(jobId) ? arrivalTimeCasse.get(jobId)
                : (arrivalTimeCasseOnline.containsKey(jobId) ? arrivalTimeCasseOnline.get(jobId) : null);
        if (ingresso != null) {
            listSystemResponse.add(t.current - ingresso);
        }

        globalStatistics.registerSuccessfulExit(t.current);
    }

    public void run(int[] inventory_setup) {
        // Inizializzazione come prima
        resetNextId();
        initGenerators();
        initInventorySystem(inventory_setup[0],inventory_setup[1],inventory_setup[2],inventory_setup[3],inventory_setup[4]);
        initCenters(new int[]{5, 2});
        initEvents();
        t = new Time(0, 0);
        closeTheDoor = false;
        globalStatistics = new Statistics(1);
        this.fakeAbandon = new ArrayList<>();

        // ⚠️ CORREZIONE: Inizializza inventorySystem in ENTRAMBI i bracci
        if (braccioUno instanceof PriorityQueueCenter) {
            ((PriorityQueueCenter) braccioUno).inventorySystem = inventorySystem;
        }

        // ⚠️ AGGIUNTA: Anche BraccioDue ha bisogno dell'inventorySystem
        braccioDue.inventorySystem = inventorySystem;

        generateAllExternalArrivals();
        generateTimeChange();

        // Ciclo principale con controllo manuale
        while (!closeTheDoor || !events.isEmpty()) {

            Event event = events.poll();
            if (event == null) {
                break;
            }

            t.current = event.getTime();
            inventorySystem.updateTime(t.current);

            // Micro-evento di carico incrementale: deposita 1 unità a banco (SOLO inventario). Il tempo
            // è già avanzato da updateTime sopra → integrale holding corretto. Nessun centro/coda.
            if (event.getType() == EventType.RESTOCK) {
                inventorySystem.receiveDelivery(
                        SimulationValues.getClassIdFromName(event.getClasseFarmaco()), 1, t.current, event.getId());
                continue;
            }

            Center currentCenter = event.getCenter();
            if (currentCenter == null && event.getType() != EventType.TIMECHANGE && event.getType() != EventType.SAMPLING && event.getType() != EventType.SLOTCHANGE) {
                throw new IllegalStateException("Evento senza centro associato!");
            }

            // 🔧 AGGIUNTA CRITICA:
            if (currentCenter != null) {
                // currentCenter.currentEvent = event;
                currentCenter.updateStatistics(event);
            }


            switch (event.getType()) {

                case ARRIVAL:
                    currentCenter.currentEvent = event;

                    event.setFirstArrivalTime(t.current);
                    if(currentCenter instanceof Casse ) {
                        arrivalTimeCasse.put(event.getId(),t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if(currentCenter instanceof CassaOnline) {
                        arrivalTimeCasseOnline.put(event.getId(),t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if(currentCenter instanceof Dispatcher) {
                        arrivalTimeDispatcher.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof BraccioUno ) {
                        arrivalTimeBraccio1.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof BraccioDue ) {
                        arrivalTimeBraccio2.put(event.getId(),t.current);
//                        try (BufferedWriter writer = new BufferedWriter(new FileWriter("BraccioDue_arrival.dat", true))) {
//                            writer.write(String.valueOf((Double) t.current));
//                            writer.newLine();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }

                    }
                    if(currentCenter instanceof Magazziniere) {
                        arrivalTimeMagazziniere.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof Pagamento) {
                        arrivalTimePagamento.put(event.getId(),t.current);
                    }

                    int arrivalResult = currentCenter.processArrival();

                    logJobEvent("ARRIVAL", event, currentCenter,
                            arrivalResult == -1 ? "→ CODA" : "→ SERVIZIO_" + arrivalResult);
                    if (arrivalResult >= 0 && currentCenter instanceof Casse) {
                        // L'ordine porta con sé l'indice del server cassa (per il routing/log);
                        // il rilascio non dipende più da una mappa esterna.
                        event.setOriginalCassaId(arrivalResult);

                    }

                    // ⚠️ GESTIONE ABBANDONI: Solo per clienti fisici che vanno in coda
                    if (ABANDONMENT_ENABLED && event.isExternal() && arrivalResult == -1 && !event.checkOnline()) {
                        double pazienza ;
                        r.selectStream(StreamType.STREAM_P_ABANDON);
                        double random = r.random();
                        if (random <= 0.03) {
                            pazienza = v.boundedNormal(60.0, 20.0, 30.0, 180.0);
                        }
                        else if(random > 0.03 && random <= 0.2 ) {
                            pazienza = v.boundedNormal(800.0, 50.0, 650.0, 1000.0);
                        }
                        else {
                            pazienza = v.boundedNormal(600.0, 90.0, 500.0, 750.0);
                        }
                        double abandonTime = t.current + pazienza;
                       // System.out.println("JOB_" + event.getId() + " PROGRAMMA ABBANDONO AL TEMPO: " + abandonTime);
                        Event abandon = new Event(EventType.ABANDON, abandonTime);
                        abandon.setCenter(currentCenter);
                        abandon.setId(event.getId());
                        abandon.setClasseFarmaco(event.getClasseFarmaco());
                        abandon.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                        events.add(abandon);
                    }

                    if (currentCenter instanceof MssqCenter) {
                        if (arrivalResult >= 0 && arrivalResult < currentCenter.servers.size()) {

                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            departure.setId(event.getId());
                            departure.setClasseFarmaco(event.getClasseFarmaco());
                            departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                            departure.setOnline(event.checkOnline());
                            departure.setExternal(event.isExternal());
                            departure.setMittente(event.getMittente());
                            departure.setOriginalCassaId(arrivalResult);

                            events.add(departure);
                        }
                    } else {
                        if (arrivalResult == 0) {

                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            departure.setId(event.getId());
                            departure.setClasseFarmaco(event.getClasseFarmaco());
                            departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                            departure.setOnline(event.checkOnline());
                            departure.setExternal(event.isExternal());
                            departure.setMittente(event.getMittente());
                            if (currentCenter instanceof CassaOnline){
                                departure.setOriginalCassaId(0);
                            }
                            else {
                                departure.setOriginalCassaId(event.getOriginalCassaId());
                            }
                            events.add(departure);
                        }
                    }

                    break;

                case DEPARTURE:
                    currentCenter.currentEvent = event;
                    int departedJobId = event.getId();

                    if (currentCenter instanceof Casse ) {
                        Double response = t.current - arrivalTimeCasse.get(departedJobId);
                        listCasseResponse.add(response);
                    }

                    if (currentCenter instanceof CassaOnline ) {
                        Double response = t.current - arrivalTimeCasseOnline.get(departedJobId);
                        listCasseOnlineResponse.add(response);
                    }

                    if(currentCenter instanceof Dispatcher) {
                        Double response = t.current - arrivalTimeDispatcher.get(departedJobId);
                        listDispatcherResponse.add(response);
                    }
                    if(currentCenter instanceof BraccioUno ) {
                        Double response = t.current - arrivalTimeBraccio1.get(departedJobId);
                        listBraccio1Response.add(response);
                    }
                    if(currentCenter instanceof BraccioDue) {
                        Double response = t.current - arrivalTimeBraccio2.get(departedJobId);
                        listBraccio2Response.add(response);
                    }
                    if(currentCenter instanceof Magazziniere) {
                        Double response = t.current - arrivalTimeMagazziniere.get(departedJobId);
                        listMagazziniereResponse.add(response);
                    }
                    if(currentCenter instanceof Pagamento && arrivalTimePagamento.containsKey(departedJobId)) {
                        listPagamentoResponse.add(t.current - arrivalTimePagamento.get(departedJobId));
                    }


                    int departureResult = currentCenter.processDeparture();


                    String departureDetails = "";
                    if (currentCenter.getNextCenter() == 0) {
                        departureDetails = "→ USCITA SISTEMA";
                    } else {
                        departureDetails = "→ CENTRO_" + currentCenter.getNextCenter();
                    }

                    if (departureResult == 0) {
                        departureDetails += ", CODA_NON_VUOTA";
                    } else if (departureResult == -2) {
                        departureDetails += ", OUT_OF_STOCK";
                    }

                    logJobEvent("DEPARTURE", event, currentCenter, departureDetails);

                    // --- Magazziniere ---
                    if (currentCenter instanceof Magazziniere) {
                        handleMagazziniere(event, currentCenter, departureResult);
                        break;
                    }

                    // --- Dispatcher ---
                    if (currentCenter instanceof Dispatcher) {
                        handleDispatcher(event, currentCenter, departureResult);
                        break;
                    }

                    // --- Casse Farmacia ---
                    if (currentCenter instanceof Casse) {
                        handleCasse(event, currentCenter, departureResult);
                        break;
                    }

                    // --- Casse Pagamento ---
                    if (currentCenter instanceof Pagamento) {
                        handlePagamento(event, currentCenter, departureResult);
                        break;
                    }

                    // --- Bracci ---
                    if (currentCenter instanceof BraccioDue) {
                        handleBraccioDue(event, currentCenter, departureResult,departedJobId);
                        break;
                    }

                    if (currentCenter instanceof BraccioUno) {
                        handleBraccioUno(event, currentCenter, departureResult,departedJobId);
                        break;
                    }

                    // --- Cassa Online ---
                    if (currentCenter instanceof CassaOnline) {
                        handleCassaOnline(event, currentCenter, departureResult);
                        break;
                    }
                    break;

                case ABANDON:
                    handleAbandon(event);  // registra perdita e rimuove da coda
                    break;

                case TIMECHANGE:
                    handleTimeChange(event,STOP);  // aggiorna λ(t) e controlla soglie scorte
                    break;

                case COMPLETION:
                    handleCompletion(event);
                    break;
                default:
                    System.out.println("Evento non riconosciuto: " + event);
            }

            // 4. Fine degli arrivi esterni
            if (t.current > STOP && !closeTheDoor) {
                closeTheDoor = true;
                t.last = t.current;
            }
        }

        System.out.println(" ");
        System.out.println("----------Statistiche simulazione----------");
        System.out.println("Completamenti Clienti con successo: " + globalStatistics.totalSuccessfulExits);
        System.out.println("Ordini di rifornimento caricati: " + globalStatistics.highPriorityCompletions);
        System.out.println("Ordini cliente serviti ai bracci: " + (globalStatistics.lowPriorityCompletions + globalStatistics.braccioDueCompletions));
        System.out.println("Farmaci Caricati (per-articolo): " + globalStatistics.farmaciCaricati);
        System.out.println("Farmaci Venduti (per-articolo): " + globalStatistics.farmaciVenduti);
        System.out.println("Abbandoni Clienti: " + globalStatistics.totalReneging);
        System.out.println("Out of stock: " + globalStatistics.totalOutOfStock);
        System.out.println("Totale job generati: " + globalStatistics.totalGeneratedJob);
        System.out.println("Totale farmaci richiesti generati: " + globalStatistics.totalGeneratedFarmaci);
        System.out.println("Farmaci persi in abbandoni: " + farmaciPersi);
        System.out.println("Richieste non completate totali: " + notCompleted.size());
        System.out.println("Job entrati in coda Cassa: "+casse.contaJobCoda);
        System.out.println("Job entrati in coda Cassa Online: "+cassaOnline.contaJobCoda);
        System.out.println("Job completati cassa Online in coda Cassa: "+globalStatistics.cassaOnlineCompletions);
        System.out.println("job in uscita da dispatcher: "+globalStatistics.jobSpacchettati);
        System.out.println("job in entrata al braccio due: "+braccioDue.arrivedJob+" di cui: "+braccioDue.directQueue+" in coda e "+braccioDue.directService+" direttamente in servizio e partenti: "+braccioDue.departingJobs+" di cui estratti dalla coda "+braccioDue.extractedFromQueue);
        System.out.println("job in entrata al braccio uno coda bassa: "+braccioUno.arrivedJob+" di cui: "+braccioUno.directQueue+" in coda e "+braccioUno.directService+" direttamente in servizio e partenti: "+braccioUno.departingJobs+" di cui estratti dalla coda "+braccioUno.extractedFromQueue);
        System.out.println("");
          double mediaGlob = meanSafe(listGlobalResponse);
        System.out.println("response time medio : "+ mediaGlob );

        double mediaC    = meanSafe(listCasseResponse);
        System.out.println("response time medio Casse : "+ mediaC );
        media = 0;
        for (int i = 0; i<casse.waitingTimes.size();i++){
            media += casse.waitingTimes.get(i);
        }
        media = media / casse.waitingTimes.size();
        System.out.println("waiting time medio Casse : "+ media );

        double mediaO    = meanSafe(listCasseOnlineResponse);
        System.out.println("response time medio Casse Online : "+ mediaO );

        double mediaD    = meanSafe(listDispatcherResponse);
        System.out.println("response time medio Dispatcher : "+ mediaD );

        double mediaB1   = meanSafe(listBraccio1Response);
        System.out.println("response time medio Braccio Uno : "+ mediaB1 );

        double mediaB2   = meanSafe(listBraccio2Response);
        System.out.println("response time medio Braccio Due : "+ mediaB2 );

        // Tempo MEDIO DI SERVIZIO dei bracci (verifica del servizio dinamico k·μ):
        // atteso ≈ E[K]·15s ≈ 2,71·15 ≈ 40,6s per il prelievo (Braccio Due solo prelievi).
        System.out.printf("tempo di servizio medio Braccio Uno : %.2f s%n", braccioUno.getAvgService(0));
        System.out.printf("tempo di servizio medio Braccio Due : %.2f s%n", braccioDue.getAvgService(0));
        System.out.printf("tempo di servizio medio Magazziniere : %.2f s%n", avgServiceMssq(magazziniere));

        double mediaM    = meanSafe(listMagazziniereResponse);
        System.out.println("response time medio Magazziniere: "+ mediaM );

        double mediaPag  = meanSafe(listPagamentoResponse);
        System.out.println("response time medio Casse Pagamento : "+ mediaPag );

        List<Integer> newlist = new ArrayList<>();
        for (int i = 0; i < listOutOfStock.size(); i++) {
            newlist.add(listOutOfStock.get(i)); // ora gli ID sono per-ordine (niente più *1000)
        }

        int count = 0;
        for (Integer value : notCompleted) {
            if (!newlist.contains(value)) {
                count++;
                //System.out.println(count + ") " + value);
            }
        }
        //stampa le statistiche di ogni centro
        printCentersStatistics();

        //stampa le statistiche dell'intero sistema
        printSystemStatistics();

        inventorySystem.printResults();
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter("Shortage_range_5.dat", true))) {
//            writer.write(Double.toString(inventorySystem.getTotalShortageCost()));
//            writer.newLine();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter("TotalCost_range_5.dat", true))) {
//            writer.write(Double.toString(inventorySystem.getTotalSystemCost()));
//            writer.newLine();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        try (BufferedWriter writer = new BufferedWriter(new FileWriter("GlobalResp_range_5.dat", true))) {
//            writer.write(Double.toString(mediaGlob));
//            writer.newLine();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
        System.out.println("Total Cost: "+inventorySystem.getTotalSystemCost());
        System.out.println("Shortage Cost: "+inventorySystem.getTotalShortageCost());

        // ── TEMPO DI RISPOSTA DEL SISTEMA: due metodi indipendenti, da confrontare ──────────────
        // I centri NON-cliente sono esclusi: il Magazziniere e la coda di carico (HIGH) del Braccio
        // Uno trattano rifornimenti interni, non clienti.
        //
        // METODO A — somma dei tempi di risposta (nodo = attesa+servizio) dei centri attraversati
        // dal cliente, mediati sulle alternative in parallelo secondo i rispettivi completamenti:
        //   W_sys = W_ingresso + W_dispatcher + W_bracci + W_pagamento
        // - W_ingresso : il cliente entra dalla cassa fisica O dalla cassa online (media pesata)
        // - W_bracci   : il prelievo va a Braccio Uno (SOLO coda LOW = clienti) O a Braccio Due
        // - W_pagamento: solo i FISICI lo attraversano → pesato sulla frazione di clienti fisici
        double nPhys = globalStatistics.cassaCompletions;
        double nOnline = globalStatistics.cassaOnlineCompletions;
        double nTot = nPhys + nOnline;

        double respCasse  = casse.getAvgWait(0, globalStatistics);
        double respOnline = cassaOnline.getAvgWait(0, globalStatistics);
        double responseIncome = (nTot > 0) ? (respCasse*nPhys + respOnline*nOnline) / nTot : 0.0;

        double responseDispatcher = dispatcher.getAvgWait(0, globalStatistics);

        double respB1 = braccioUno.getAvgWait(PriorityQueueCenter.LOW_PRIORITY, globalStatistics); // SOLO clienti (no carico)
        double respB2 = braccioDue.getAvgWait(0, globalStatistics);
        double nB1 = globalStatistics.lowPriorityCompletions;
        double nB2 = globalStatistics.braccioDueCompletions;
        double responseService = (nB1 + nB2 > 0) ? (respB1*nB1 + respB2*nB2) / (nB1 + nB2) : 0.0;

        double respPagamentoNodo = pagamento.getAvgWait(0, globalStatistics);     // W del nodo Pagamento
        double responsePagamento = (nTot > 0) ? respPagamentoNodo * nPhys / nTot : 0.0; // pesato sui fisici

        double SysRespA = responseIncome + responseDispatcher + responseService + responsePagamento;

        // METODO B — timestamp diretto: media di (uscita dal sistema − ingresso) su TUTTI i clienti
        // (fisici: cassa→pagamento; online: cassa online→uscita dal braccio). Misura indipendente.
        double SysRespB = meanSafe(listSystemResponse);

        config_resp = SysRespA;
        config_cost = inventorySystem.getTotalSystemCost();

        System.out.println("\n--- TEMPO DI RISPOSTA DEL SISTEMA (escl. magazziniere e carico) ---");
        System.out.printf("  [A] Somma W per-centro ......... = %.3f s%n", SysRespA);
        System.out.printf("      (ingresso=%.2f + dispatcher=%.2f + bracci=%.2f + pagamento=%.2f)%n",
                responseIncome, responseDispatcher, responseService, responsePagamento);
        System.out.printf("  [B] Timestamp ingresso→uscita .. = %.3f s%n", SysRespB);
        if (SysRespB > 0) {
            System.out.printf("      scarto A vs B ............. = %.2f%%%n", 100.0*(SysRespA - SysRespB)/SysRespB);
        }
    }

    public void runFiniteHorizonSimulation(int[][] configCenters, double[] slotRates, int[] inventory_setup) {
        int jobsProcessed = 0;                                  // jobs processati nel sampling corrente
        int samplingIndex = 0;                                  // tiene traccia dello slot di sampling correntemente simulato
        matrix = new Statistics[7][numSampling];                // 7 centri (incl. CassePagamento); statistiche medie per centro e per slot

        resetNextId();
        // Inizializza i centri con la configurazione della prima fascia oraria
        initInventorySystem(inventory_setup[0], inventory_setup[1], inventory_setup[2], inventory_setup[3], inventory_setup[4]);
        initCenters(new int[]{5, 2});
        initEvents();

        // Indice che tiene traccia della fascia oraria corrente
        int slotIndex = 0;

        // Inizializza il clock di simulazione
        t = new Time(0, 0);
        closeTheDoor = false;
        globalStatistics = new Statistics(1);
        this.fakeAbandon = new ArrayList<>();

        // Inizializza inventorySystem in ENTRAMBI i bracci
        if (braccioUno instanceof PriorityQueueCenter) {
            ((PriorityQueueCenter) braccioUno).inventorySystem = inventorySystem;
        }
        braccioDue.inventorySystem = inventorySystem;

        // Produce gli eventi di campionamento
        generateSamplingEvents();

        // Configura il tasso di arrivo della prima fascia oraria
        setArrivalRate(slotRates[slotIndex]);
        slotIndex++;

        generateAllExternalArrivals();
        generateTimeChange();
        changeConfigurationCenters(configCenters[0]);

        while (!closeTheDoor || !events.isEmpty()) {

            Event event = events.poll();
            if (event == null) {
                break;
            }

            t.current = event.getTime();
            inventorySystem.updateTime(t.current);

            // Micro-evento di carico incrementale: deposita 1 unità a banco (SOLO inventario). Il tempo
            // è già avanzato da updateTime sopra → integrale holding corretto. Nessun centro/coda.
            if (event.getType() == EventType.RESTOCK) {
                inventorySystem.receiveDelivery(
                        SimulationValues.getClassIdFromName(event.getClasseFarmaco()), 1, t.current, event.getId());
                continue;
            }

            Center currentCenter = event.getCenter();
            if (currentCenter == null && event.getType() != EventType.TIMECHANGE && event.getType() != EventType.SAMPLING && event.getType() != EventType.SLOTCHANGE) {
                throw new IllegalStateException("Evento senza centro associato!");
            }

            if (currentCenter != null) {
                currentCenter.updateStatistics(event);
            }

            switch (event.getType()) {

                case ARRIVAL:
                    currentCenter.currentEvent = event;

                    event.setFirstArrivalTime(t.current);

                    // --- CORREZIONE: Separazione logica mappa arrivi ---
                    if (currentCenter instanceof Casse) {
                        arrivalTimeCasse.put(event.getId(), t.current);
                        globalStatistics.registerGeneratedJob(); // conta arrivo nella finestra corrente
                    } else if (currentCenter instanceof CassaOnline) {
                        arrivalTimeCasseOnline.put(event.getId(), t.current);
                        globalStatistics.registerGeneratedJob(); // conta arrivo nella finestra corrente
                    }
                    // --------------------------------------------------

                    if (currentCenter instanceof Dispatcher) {
                        arrivalTimeDispatcher.put(event.getId(), t.current);
                    }
                    if (currentCenter instanceof BraccioUno || currentCenter instanceof BraccioDue) {
                        arrivalTimeBraccio.put(event.getId(), t.current);
                    }
                    if (currentCenter instanceof Magazziniere) {
                        arrivalTimeMagazziniere.put(event.getId(), t.current);
                    }
                    if (currentCenter instanceof Pagamento) {
                        arrivalTimePagamento.put(event.getId(), t.current);
                    }

                    int arrivalResult = currentCenter.processArrival();

                    logJobEvent("ARRIVAL", event, currentCenter,
                            arrivalResult == -1 ? "→ CODA" : "→ SERVIZIO_" + arrivalResult);
                    if (arrivalResult >= 0 && currentCenter instanceof Casse) {
                        // L'ordine porta con sé l'indice del server cassa (per il routing/log);
                        // il rilascio non dipende più da una mappa esterna.
                        event.setOriginalCassaId(arrivalResult);
                    }

                    // GESTIONE ABBANDONI: Solo per clienti fisici che vanno in coda
                    if (ABANDONMENT_ENABLED && event.isExternal() && arrivalResult == -1 && !event.checkOnline()) {
                        double pazienza;
                        r.selectStream(StreamType.STREAM_P_ABANDON);
                        double random = r.random();
                        if (random <= 0.03) {
                            pazienza = v.boundedNormal(60.0, 20.0, 30.0, 180.0);
                        } else if (random > 0.03 && random <= 0.2) {
                            pazienza = v.boundedNormal(200.0, 50.0, 180.0, 350.0);
                        } else {
                            pazienza = v.boundedNormal(400.0, 90.0, 350.0, 650.0);
                        }
                        double abandonTime = t.current + pazienza;
                        Event abandon = new Event(EventType.ABANDON, abandonTime);
                        abandon.setCenter(currentCenter);
                        abandon.setId(event.getId());
                        abandon.setClasseFarmaco(event.getClasseFarmaco());
                        abandon.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                        events.add(abandon);
                    }

                    if (currentCenter instanceof MssqCenter) {
                        if (arrivalResult >= 0 && arrivalResult < currentCenter.servers.size()) {
                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            departure.setId(event.getId());
                            departure.setClasseFarmaco(event.getClasseFarmaco());
                            departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                            departure.setOnline(event.checkOnline());
                            departure.setExternal(event.isExternal());
                            departure.setMittente(event.getMittente());
                            departure.setOriginalCassaId(arrivalResult);
                            events.add(departure);
                        }
                    } else {
                        if (arrivalResult == 0) {
                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            departure.setId(event.getId());
                            departure.setClasseFarmaco(event.getClasseFarmaco());
                            departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                            departure.setOnline(event.checkOnline());
                            departure.setExternal(event.isExternal());
                            departure.setMittente(event.getMittente());
                            if (currentCenter instanceof CassaOnline) {
                                departure.setOriginalCassaId(0);
                            } else {
                                departure.setOriginalCassaId(event.getOriginalCassaId());
                            }
                            events.add(departure);
                        }
                    }
                    break;

                case DEPARTURE:
                    currentCenter.currentEvent = event;

                    int departedJobId = event.getId();

                    // --- CORREZIONE: Separazione logica raccolta Response Times ---
                    if (currentCenter instanceof Casse) {
                        Double response = t.current - arrivalTimeCasse.get(departedJobId);
                        listCasseResponse.add(response);
                    } else if (currentCenter instanceof CassaOnline) {
                        Double response = t.current - arrivalTimeCasseOnline.get(departedJobId);
                        listCasseOnlineResponse.add(response);
                    }
                    // --------------------------------------------------------------

                    if (currentCenter instanceof Dispatcher) {
                        Double response = t.current - arrivalTimeDispatcher.get(departedJobId);
                        listDispatcherResponse.add(response);
                    }
                    if (currentCenter instanceof BraccioUno || currentCenter instanceof BraccioDue) {
                        Double response = t.current - arrivalTimeBraccio.get(departedJobId);
                        listBraccioResponse.add(response);
                    }
                    if (currentCenter instanceof Magazziniere) {
                        Double response = t.current - arrivalTimeMagazziniere.get(departedJobId);
                        listMagazziniereResponse.add(response);
                    }
                    if (currentCenter instanceof Pagamento && arrivalTimePagamento.containsKey(departedJobId)) {
                        listPagamentoResponse.add(t.current - arrivalTimePagamento.get(departedJobId));
                    }

                    int departureResult = currentCenter.processDeparture();

                    String departureDetails = "";
                    if (currentCenter.getNextCenter() == 0) {
                        departureDetails = "→ USCITA SISTEMA";
                    } else {
                        departureDetails = "→ CENTRO_" + currentCenter.getNextCenter();
                    }

                    if (departureResult == 0) {
                        departureDetails += ", CODA_NON_VUOTA";
                    } else if (departureResult == -2) {
                        departureDetails += ", OUT_OF_STOCK";
                    }

                    logJobEvent("DEPARTURE", event, currentCenter, departureDetails);

                    // --- Routing agli handler specifici ---
                    if (currentCenter instanceof Magazziniere) {
                        handleMagazziniere(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Dispatcher) {
                        handleDispatcher(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Casse) {
                        handleCasse(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Pagamento) {
                        handlePagamento(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof BraccioDue) {
                        handleBraccioDue(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof BraccioUno) {
                        handleBraccioUno(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof CassaOnline) {
                        handleCassaOnline(event, currentCenter, departureResult);
                        break;
                    }
                    break;

                case ABANDON:
                    handleAbandon(event);
                    break;

                case TIMECHANGE:
                    handleTimeChange(event, STOP);
                    int fasciaIndex = (int) (event.getTime() / 1800);
                    if (fasciaIndex < configCenters.length) {
                        changeConfigurationCenters(configCenters[fasciaIndex]);
                    }
                    break;

                case SAMPLING:
                    generateSamplingStatistics(samplingIndex, t.current);
                    snapTime = t.current;   // aggiorna prima del reset per resetLastUpdateTime corretto
                    resetStatistics();
                    samplingIndex++;
                    break;

                case COMPLETION:
                    handleCompletion(event);
                    break;
                default:
                    System.out.println("Evento non riconosciuto: " + event);
            }

            if (t.current > STOP && !closeTheDoor) {
                closeTheDoor = true;
                t.last = t.current;
            }
        }

        // Stampa finale statistiche (per debug della singola run, utile anche in finite horizon)
//        System.out.println(" ");
//        System.out.println("----------Statistiche simulazione (Replica Corrente)----------");
//        System.out.println("Completamenti Clienti con successo: " + globalStatistics.totalSuccessfulExits);
//        System.out.println("Farmaci Caricati: " + globalStatistics.highPriorityCompletions);
//        System.out.println("Farmaci Venduti: " + globalStatistics.totalSuccessfulExitsBracci);
//        System.out.println("Abbandoni Clienti: " + globalStatistics.totalReneging);
//        System.out.println("Out of stock: " + globalStatistics.totalOutOfStock);
//        System.out.println("Totale job generati: " + globalStatistics.totalGeneratedJob);

        //printCentersStatistics();
        //printSystemStatistics();

        // ── RIEPILOGO PER REPLICA ──────────────────────────────────────────────────
        int totArrivi    = arrivalsCasse + arrivalsCassaOnline;
        int totServiti   = listGlobalResponse.size();   // COMPLETION fisiche + online
        int totAbbandoni = leavingJob;

        // Clienti unici con almeno un farmaco out-of-stock:
        // listOutOfStock contiene ora l'ID dell'ORDINE (un'entry per articolo OOS) → uso l'ID diretto.
        Set<Integer> uniqueOosParents = new HashSet<>(listOutOfStock);
        int totOutOfStock = uniqueOosParents.size();

        if (verboseOutput) {
        System.out.printf(
            "  [REPLICA] Arrivi: %d (fisici=%d, online=%d) | Serviti: %d | Abbandoni: %d | OutOfStock: %d%n",
            totArrivi, arrivalsCasse, arrivalsCassaOnline,
            totServiti, totAbbandoni, totOutOfStock
        );
        System.out.printf(
            "  [REPLICA] Farmaci Venduti (art.): %d | Farmaci Caricati (art.): %d | Farmaci richiesti: %d%n",
            globalStatistics.farmaciVenduti, globalStatistics.farmaciCaricati, globalStatistics.totalGeneratedFarmaci
        );

        // ── DETTAGLIO PRIMA ORA (finestre 0-7, t=0..3600s) ────────────────────────
        // matrix[0] = Casse Fisiche
        System.out.println("  [PRIMA ORA] Win | Arrivi | Complet.Casse | E[N]  | E[Nq] | E[T]s");
        for (int si = 0; si < Math.min(8, numSampling); si++) {
            Statistics s = matrix[0][si];
            if (s == null) {
                System.out.printf("  [PRIMA ORA]  %2d |   null%n", si);
                continue;
            }
            System.out.printf(
                "  [PRIMA ORA]  %2d |   %3d  |      %3d      | %5.2f | %5.2f | %6.1f%n",
                si,
                s.totalGeneratedJob,
                s.cassaCompletions,
                s.avgNode[0],
                s.avgQueue[0],
                s.avgWait[0]
            );
        }
        } // fine if (verboseOutput)
    }

    /** Entry point backward-compatible: usa 5 casse, 2 magazzinieri, stampa stime, nessun gap. */
    /** ρ medio sui batch per il centro iC; per i multi-server prende il MAX tra i server (il più
     *  carico = il bottleneck). Condiviso tra il riepilogo verbose (generateEstimate) e RunResult. */
    private double computeCenterUtilization(Statistics[][] matrix, int iC, int numBatches) {
        int slots = 1;
        for (int b = 0; b < numBatches; b++) {
            Statistics s = matrix[iC][b];
            if (s != null && s.utilization != null) slots = Math.max(slots, s.utilization.length);
        }
        double rhoCenter = 0.0;
        for (int sv = 0; sv < slots; sv++) {
            double sum = 0; int cnt = 0;
            for (int b = 0; b < numBatches; b++) {
                Statistics s = matrix[iC][b];
                if (s != null && s.utilization != null && sv < s.utilization.length) { sum += s.utilization[sv]; cnt++; }
            }
            if (cnt > 0) rhoCenter = Math.max(rhoCenter, sum / cnt);
        }
        return rhoCenter;
    }

    public void runInfiniteHorizonSimulation(int numBatches, int batchSize, int[] inventory_setup) {
        runInfiniteHorizonSimulation(numBatches, batchSize, inventory_setup, new int[]{5, 2}, true, 0);
    }

    /** Overload 5-param backward-compatible: nessun gap inter-batch. */
    public void runInfiniteHorizonSimulation(int numBatches, int batchSize, int[] inventory_setup,
                                             int[] centerConfig, boolean verbose) {
        runInfiniteHorizonSimulation(numBatches, batchSize, inventory_setup, centerConfig, verbose, 0);
    }

    /**
     * Entry point completo con gap inter-batch.
     * @param interBatchGap  job da scartare (sistema gira, stats azzerata) tra un batch e il successivo;
     *                       0 = comportamento originale senza gap
     */
    public void runInfiniteHorizonSimulation(int numBatches, int batchSize, int[] inventory_setup,
                                             int[] centerConfig, boolean verbose, int interBatchGap) {
        System.out.printf("=== SIMULAZIONE INFINITE HORIZON ===%n");
        System.out.printf("Batches: %d, Dimensione batch: %d job, Gap: %d job%n",
                numBatches, batchSize, interBatchGap);

        int jobsProcessed = 0;
        int batchIndex = 0;
        Statistics[][] matrix = new Statistics[7][numBatches];
        // Variabili di controllo (Problema A)
        int warmUpJobs = 10000;
        boolean warmUpComplete = false;
        int completedDuringWarmup = 0;
        double warmUpEndTime = 0.0;   // t.current al momento del reset warm-up, per il throughput di RunResult
        // Gap inter-batch: job completati durante la fase di decorrelazione
        int gapJobsProcessed = 0;
        boolean inGapPhase    = false;

        // Inizializzazione come nel metodo run() originale
        // Reset nextId per evitare overflow int quando questa simulazione è chiamata
        // più volte in sequenza (es. ConfigurationSearch, BatchCalibration): nextId è
        // static e si accumulerebbe fra le istanze, portando fragment-ID negativi dopo
        // ~2.1M arrivi cumulativi e conseguenti warning/lookup falliti su jobToCassa.
        resetNextId();
        initGenerators();
        initInventorySystem(inventory_setup[0],inventory_setup[1],inventory_setup[2],inventory_setup[3],inventory_setup[4]);
        if (inventorySystem != null) inventorySystem.recordHistory = false; // run lunga: niente trace livello per-articolo (anti-leak)
        initCenters(centerConfig);
        initEvents();
        t = new Time(0, 0);
        closeTheDoor = false;
        globalStatistics = new Statistics(1);
        this.notCompleted = new ArrayList<>();

        Statistics[][] batchStats = new Statistics[centers.length +1][numBatches];
        double[] batchLossProbs    = new double[numBatches];
        double[] batchAbandonProbs = new double[numBatches];
        double[] batchOosProbs     = new double[numBatches];

        // Inizializza inventorySystem nei bracci
        if (braccioUno instanceof PriorityQueueCenter) {
            ((PriorityQueueCenter) braccioUno).inventorySystem = inventorySystem;
        }
        braccioDue.inventorySystem = inventorySystem;

        // === INFINITE HORIZON: verifica λ∞ e avvia primo arrivo ===
        ensureInfiniteLambda();        // <-- al posto di enableInfiniteRuntimeArrivals()
        generateInitialArrivals();     // schedula SOLO il primo arrivo runtime (Exp(λ∞))

        // === INVENTORY TICK ogni 30 minuti (solo ordini/scorte, nessun cambio tassi) ===
        generateInventoryTick();

        boolean batchStarted = false;

        // Ciclo principale (stop quando completiamo numBatches)
        while (batchIndex < numBatches) {
            Event event = events.poll();
            if (event == null) {
                // Se la coda eventi è vuota, assicura la prosecuzione del processo di arrivo
                generateNextArrival();
                continue;
            }

            t.current = event.getTime();
            inventorySystem.updateTime(t.current);

            // Micro-evento di carico incrementale: deposita 1 unità a banco (SOLO inventario). Il tempo
            // è già avanzato da updateTime sopra → integrale holding corretto. Nessun centro/coda.
            if (event.getType() == EventType.RESTOCK) {
                inventorySystem.receiveDelivery(
                        SimulationValues.getClassIdFromName(event.getClasseFarmaco()), 1, t.current, event.getId());
                continue;
            }

            Center currentCenter = event.getCenter();
            if (currentCenter == null && event.getType() != EventType.TIMECHANGE && event.getType() != EventType.SAMPLING) {
                throw new IllegalStateException("Evento senza centro associato!");
            }

            if (currentCenter != null) {
                currentCenter.updateStatistics(event);
            }

            switch (event.getType()) {
                case ARRIVAL:
                    currentCenter.currentEvent = event;
                    event.setFirstArrivalTime(t.current);
                    if(currentCenter instanceof Casse ) {
                        arrivalTimeCasse.put(event.getId(),t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if(currentCenter instanceof CassaOnline) {
                        arrivalTimeCasseOnline.put(event.getId(),t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if(currentCenter instanceof Dispatcher) {
                        arrivalTimeDispatcher.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof BraccioUno ) {
                        arrivalTimeBraccio1.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof BraccioDue ) {
                        arrivalTimeBraccio2.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof Magazziniere) {
                        arrivalTimeMagazziniere.put(event.getId(),t.current);
                    }
                    if(currentCenter instanceof Pagamento) {
                        arrivalTimePagamento.put(event.getId(),t.current);
                    }
                    // Primo arrivo esterno del batch
                    if (!batchStarted && event.isExternal()) {
                        firstArriveSystem = t.current;
                        batchStarted = true;
                    }

                    int arrivalResult = currentCenter.processArrival();

                    // Abbandoni per clienti fisici in coda
                    if (ABANDONMENT_ENABLED && event.isExternal() && arrivalResult == -1 && !event.checkOnline()) {
                        double pazienza;
                        r.selectStream(StreamType.STREAM_P_ABANDON);
                        double random = r.random();
                        if (random <= 0.03) {
                            pazienza = v.boundedNormal(60.0, 20.0, 30.0, 180.0);
                        } else if (random <= 0.2) {
                            pazienza = v.boundedNormal(200.0, 50.0, 180.0, 350.0);
                        } else {
                            pazienza = v.boundedNormal(400.0, 90.0, 350.0, 650.0);
                        }
                        double abandonTime = t.current + pazienza;
                        Event abandon = new Event(EventType.ABANDON, abandonTime);
                        abandon.setCenter(currentCenter);
                        abandon.setId(event.getId());
                        abandon.setClasseFarmaco(event.getClasseFarmaco());
                        abandon.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                        events.add(abandon);
                    }

                    // Departure per job che partono in servizio
                    if (currentCenter instanceof MssqCenter) {
                        if (arrivalResult >= 0 && arrivalResult < currentCenter.servers.size()) {
                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            if (departure != null) {
                                departure.setId(event.getId());
                                departure.setClasseFarmaco(event.getClasseFarmaco());
                                departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                                departure.setOnline(event.checkOnline());
                                departure.setExternal(event.isExternal());
                                departure.setMittente(event.getMittente());
                                departure.setOriginalCassaId(arrivalResult);
                                events.add(departure);
                            }
                        }
                    } else {
                        if (arrivalResult == 0) {
                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            if (departure != null) {
                                departure.setId(event.getId());
                                departure.setClasseFarmaco(event.getClasseFarmaco());
                                departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                                departure.setOnline(event.checkOnline());
                                departure.setExternal(event.isExternal());
                                departure.setMittente(event.getMittente());
                                if (currentCenter instanceof CassaOnline){
                                    departure.setOriginalCassaId(0);
                                }
                                else {
                                    departure.setOriginalCassaId(event.getOriginalCassaId());
                                }
                                events.add(departure);
                            }
                        }
                    }

                    // Schedula subito il prossimo arrivo esterno (runtime, Poisson λ∞)
                    if (event.isExternal()) {
                        generateNextArrival();
                    }
                    break;

                case DEPARTURE:
                    currentCenter.currentEvent = event;

                    int departedJobId = event.getId();
                    if (currentCenter instanceof Casse ) {
                        Double response = t.current - arrivalTimeCasse.get(departedJobId);
                        listCasseResponse.add(response);
//                        try (BufferedWriter writer = new BufferedWriter(new FileWriter("Casse_response1.dat", true))) {
//                            writer.write(response+", "+arrivalTimeCasse.get(departedJobId));
//                            writer.newLine();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
                    }
                    if (currentCenter instanceof CassaOnline ) {
                        Double response = t.current - arrivalTimeCasseOnline.get(departedJobId);
                        listCasseOnlineResponse.add(response);
                    }

                    if(currentCenter instanceof Dispatcher) {
                        Double response = t.current - arrivalTimeDispatcher.get(departedJobId);
                        listDispatcherResponse.add(response);
                    }
                    if(currentCenter instanceof BraccioUno ) {
                        Double response = t.current - arrivalTimeBraccio1.get(departedJobId);
                        listBraccio1Response.add(response);
                    }
                    if(currentCenter instanceof BraccioDue) {
                        Double response = t.current - arrivalTimeBraccio2.get(departedJobId);
                        listBraccio2Response.add(response);
                    }
                    if(currentCenter instanceof Magazziniere) {
                        Double response = t.current - arrivalTimeMagazziniere.get(departedJobId);
                        listMagazziniereResponse.add(response);
                    }
                    if(currentCenter instanceof Pagamento && arrivalTimePagamento.containsKey(departedJobId)) {
                        listPagamentoResponse.add(t.current - arrivalTimePagamento.get(departedJobId));
                    }
                    int departureResult = currentCenter.processDeparture();

                    // Smista logica per centro (come run() originale)
                    if (currentCenter instanceof Magazziniere) {
                        handleMagazziniere(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Dispatcher) {
                        handleDispatcher(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Casse) {
                        handleCasse(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Pagamento) {
                        handlePagamento(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof BraccioDue) {
                        handleBraccioDue(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof BraccioUno) {
                        handleBraccioUno(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof CassaOnline) {
                        handleCassaOnline(event, currentCenter, departureResult);
                        break;
                    }
                    break;

                case ABANDON:
                    handleAbandon(event);
                    break;

                case TIMECHANGE:
                    // SOLO TICK INVENTARIO (nessun cambio dei tassi d’arrivo)
                    handleTimeChange(event,STOP_INFINITE);
                    break;

                case COMPLETION:
                    handleCompletion(event);

                    // --- GESTIONE WARM-UP (Punto A) ---
                    if (!warmUpComplete) {
                        completedDuringWarmup++;
                        if (completedDuringWarmup >= warmUpJobs) {
                            warmUpComplete = true;
                            warmUpEndTime = t.current;

                            // Reset completo di tutti i centri e dei contatori globali
                            // Questo pulisce le areeNode, areeQueue e i service accumulati nel transitorio
                            resetBatchStatistics();

                            // Azzera i cumulativi (abbandoni/OOS/...) così misurano solo la fase
                            // post-warm-up (resetBatchStatistics per-batch NON li tocca → accumulano
                            // su tutti i batch di misura).
                            globalStatistics.totalRenegingCum  = 0;
                            globalStatistics.totalOosCum       = 0;
                            globalStatistics.totalExitsCum     = 0;
                            globalStatistics.farmaciVendutiCum = 0;
                            globalStatistics.farmaciCaricatiCum= 0;
                            globalStatistics.generatiCum       = 0;

                            jobsProcessed = 0;
                            firstArriveSystem = t.current;
                            batchStarted = false;

                            System.out.printf("--- WARM-UP COMPLETATO al tempo %.2f ---\n", t.current);
                        }
                        break;
                    }

                    // --- FASE DI GAP INTER-BATCH ---
                    // Il sistema gira ma i job non contribuiscono alle statistiche del prossimo batch.
                    if (inGapPhase) {
                        gapJobsProcessed++;
                        if (gapJobsProcessed >= interBatchGap) {
                            // Fine gap: reset statistiche e avvio del nuovo batch pulito
                            resetBatchStatistics();
                            firstArriveSystem = t.current;
                            batchStarted = false;
                            jobsProcessed = 0;
                            rejectedJob = 0;
                            gapJobsProcessed = 0;
                            inGapPhase = false;
                        }
                        break;
                    }

                    // --- GESTIONE BATCH REALI ---
                    jobsProcessed++;
                    lastDepartureSystem = t.current;

                    if (jobsProcessed == batchSize) {
                        generateStatistics(batchStats, batchIndex, jobsProcessed, firstArriveSystem, lastDepartureSystem);

                        // Calcolo probabilità di perdita per il batch corrente (Punto B).
                        // ⚠️ Denominatori COERENTI con l'unità di misura del numeratore:
                        //  - abbandono = eventi PER-ORDINE (cliente) → / totalGeneratedJob (ordini);
                        //  - out-of-stock = eventi PER-ARTICOLO (un ordine chiede K articoli) →
                        //    / totalGeneratedFarmaci (articoli richiesti). Prima erano entrambi divisi
                        //    per i job: P(OOS) poteva superare 1 (saturava a E[K]≈2.86 = "tutto OOS").
                        double currentLossProp   = 0.0;
                        double currentAbandonProp = 0.0;
                        double currentOosProp     = 0.0;
                        if (globalStatistics.totalGeneratedJob > 0) {
                            currentAbandonProp = (double) globalStatistics.totalReneging
                                    / globalStatistics.totalGeneratedJob;
                        }
                        if (globalStatistics.totalGeneratedFarmaci > 0) {
                            currentOosProp     = (double) globalStatistics.totalOutOfStock
                                    / globalStatistics.totalGeneratedFarmaci;   // per-articolo, in [0,1]
                        }
                        currentLossProp = currentAbandonProp + currentOosProp;

                        batchLossProbs[batchIndex]    = currentLossProp;
                        batchAbandonProbs[batchIndex] = currentAbandonProp;
                        batchOosProbs[batchIndex]     = currentOosProp;
                        System.out.printf("Batch %d/%d completato. Job: %d, Loss Prob: %.4f\n",
                                batchIndex + 1, numBatches, jobsProcessed, currentLossProp);

                        batchIndex++;

                        if (batchIndex == numBatches) {
                            break;
                        }

                        if (interBatchGap > 0) {
                            // Entra in fase di gap: le statistiche rimangono attive finché
                            // non sono trascorsi interBatchGap job (il reset avviene alla fine del gap)
                            inGapPhase = true;
                            gapJobsProcessed = 0;
                        } else {
                            // Nessun gap: reset immediato (comportamento originale)
                            resetBatchStatistics();
                            firstArriveSystem = t.current;
                            batchStarted = false;
                            jobsProcessed = 0;
                            rejectedJob = 0;
                        }
                    }

                    if (batchIndex == numBatches) {
                        break;
                    }
                    break;
                // NB: manca il 'break' per far cadere nel default solo se vuoi il log extra
                default:
                    System.out.println("Evento non riconosciuto: " + event);
            }
        }

        System.out.println("\n=== FINE SIMULAZIONE INFINITE HORIZON ===");

//        for (int i = 0; i < listGlobalResponse.size(); i++) {
//            media += listGlobalResponse.get(i);
//        }
//        media = media / listGlobalResponse.size();
//        System.out.println("response time medio : "+ media );
//        media = 0;
//        for (int i = 0; i < listCasseResponse.size(); i++) {
//            media += listCasseResponse.get(i);
//        }
//        media = media / listCasseResponse.size();
//        System.out.println("response time medio Casse : "+ media );

        if (verbose) generateEstimate(batchStats, numBatches);
        writeBatchDatFiles(batchStats, batchLossProbs, numBatches);
        if (inventorySystem != null)
            inventorySystem.writeLevelTimeSeries(System.getProperty("stats.dir", "stats/infinite"));

        // ── RIEPILOGO AGGREGATO POST-WARM-UP (abbandoni, OOS, ecc.) ───────────────
        if (verbose) {
            double[] sum = getRunSummary(); // cumulativi sull'intera fase di misura
            double gen = sum[5], vend = sum[3], car = sum[4], abb = sum[0], oos = sum[1], exits = sum[2];
            System.out.println("\n" + "=".repeat(60));
            System.out.println("   RIEPILOGO AGGREGATO (post warm-up, " + numBatches + " batch)");
            System.out.println("=".repeat(60));
            System.out.printf("  Clienti serviti .................... = %.0f%n", exits);
            System.out.printf("  Abbandoni .......................... = %.0f%n", abb);
            System.out.printf("  Farmaci richiesti / venduti / caricati = %.0f / %.0f / %.0f%n", gen, vend, car);
            System.out.printf("  Out-of-stock articoli .............. = %.0f%n", oos);
            System.out.printf("  P(out-of-stock) articoli ........... = %.3f%n", gen > 0 ? oos / gen : 0.0);
            System.out.printf("  P(abbandono) clienti ............... = %.3f%n", (exits + abb) > 0 ? abb / (exits + abb) : 0.0);
            System.out.printf("  Costo inventario (EUR) ............. = %.2f%n", sum[6]);
            System.out.printf("  Delivery lag medio (min) ........... = %.1f%n", sum[7] / 60.0);
        }
        // DIAGNOSTICA per-classe (domanda vs unità ordinate vs livello + CHECK FLUSSO) — solo verbose.
        if (verbose && inventorySystem != null) inventorySystem.printResults();

        // ── Popola lastRunResult per ConfigurationSearch ──────────────────────
        double[] batchSysRT     = new double[numBatches];
        double[] batchUtilCasse = new double[numBatches];
        double[] batchUtilMag   = new double[numBatches];
        double[][] batchRespPerCenter = new double[centers.length][numBatches]; // response per-centro per batch
        for (int b = 0; b < numBatches; b++) {
            Statistics sys = batchStats[centers.length][b];
            batchSysRT[b] = (sys != null && sys.avgWait != null && sys.avgWait.length > 0)
                    ? sys.avgWait[0] : 0.0;

            // Response per-centro (avgWait[0] = W del nodo) per la verifica Chatfield centro-per-centro
            for (int c = 0; c < centers.length; c++) {
                Statistics scj = batchStats[c][b];
                batchRespPerCenter[c][b] = (scj != null && scj.avgWait != null && scj.avgWait.length > 0)
                        ? scj.avgWait[0] : 0.0;
            }

            Statistics sc = batchStats[0][b]; // Casse (indice 0)
            if (sc != null && sc.utilization != null && sc.utilization.length > 0) {
                double sum = 0;
                for (double u : sc.utilization) sum += u;
                batchUtilCasse[b] = sum / sc.utilization.length;
            }

            Statistics sm = batchStats[5][b]; // Magazziniere (indice 5)
            if (sm != null && sm.utilization != null && sm.utilization.length > 0) {
                double sum = 0;
                for (double u : sm.utilization) sum += u;
                batchUtilMag[b] = sum / sm.utilization.length;
            }
        }
        double sumRT = 0, sumLP = 0, sumAP = 0, sumOP = 0, sumUC = 0, sumUM = 0;
        for (int b = 0; b < numBatches; b++) {
            sumRT += batchSysRT[b];
            sumLP += batchLossProbs[b];
            sumAP += batchAbandonProbs[b];
            sumOP += batchOosProbs[b];
            sumUC += batchUtilCasse[b];
            sumUM += batchUtilMag[b];
        }
        // ρ per centro (tutti i 7) + split Braccio Uno (HIGH/LOW) + throughput di sistema, per
        // ConfigurationSearch (§21.6): il costo/staffing si valuta su queste metriche, non su uno
        // score interno alla run.
        String[] centerNames  = new String[centers.length];
        double[] utilPerCenter = new double[centers.length];
        for (int c = 0; c < centers.length; c++) {
            centerNames[c]   = centers[c].name;
            utilPerCenter[c] = computeCenterUtilization(batchStats, c, numBatches);
        }
        double sumB1High = 0, sumB1Low = 0; int cntB1 = 0;
        for (int b = 0; b < numBatches; b++) {
            Statistics s = batchStats[3][b]; // Braccio Uno (indice 3)
            if (s != null) { sumB1High += s.utilHigh; sumB1Low += s.utilLow; cntB1++; }
        }
        // Throughput misurato solo sulla fase post warm-up (numBatches*batchSize completamenti
        // nell'intervallo [warmUpEndTime, lastDepartureSystem]).
        double measuredSeconds = lastDepartureSystem - warmUpEndTime;
        double throughput = (measuredSeconds > 0) ? (numBatches * (double) batchSize) / measuredSeconds : 0.0;

        RunResult rr = new RunResult();
        rr.batchSysRT         = batchSysRT;
        rr.batchLossProb      = batchLossProbs;
        rr.batchAbandonProb   = batchAbandonProbs;
        rr.batchOosProb       = batchOosProbs;
        rr.batchUtilCasse     = batchUtilCasse;
        rr.batchUtilMag       = batchUtilMag;
        rr.batchRespPerCenter = batchRespPerCenter;
        rr.sysResponseTime    = sumRT / numBatches;
        rr.lossProbability    = sumLP / numBatches;
        rr.abandonProbability = sumAP / numBatches;
        rr.oosProbability     = sumOP / numBatches;
        rr.utilCasse          = sumUC / numBatches;
        rr.utilMagazziniere   = sumUM / numBatches;
        rr.centerNames        = centerNames;
        rr.utilPerCenter      = utilPerCenter;
        rr.utilBraccioUnoHigh = cntB1 > 0 ? sumB1High / cntB1 : 0.0;
        rr.utilBraccioUnoLow  = cntB1 > 0 ? sumB1Low  / cntB1 : 0.0;
        rr.throughput         = throughput;
        lastRunResult = rr;
    }

    // ===== METODI DI SUPPORTO INFINITE=====

    private void generateInventoryTick() {
        /**
         * Pianifica il PRIMO tick di inventario.
         * Esegue un tick ogni 30 minuti, senza modificare i tassi di arrivo.
         * Allinea semplicemente il primo tick a 1800s (come 8:30 se t=0 è 8:00).
         */
        // Se parti da t=0 (08:00), il primo tick è a 1800s (08:30)
        int firstTick = INVENTORY_SLOT_SECONDS;
        events.add(new Event(EventType.TIMECHANGE, firstTick));
        //ln("Pianificato primo INVENTORY_TICK alle ore " + (firstTick / 3600.0));
    }

    private void handleInventoryTick(Event event) {
        /**
         * Gestisce il tick: controlla scorte e genera eventuali ordini,
         * poi pianifica il prossimo tick a +30 minuti.
         * NON modifica i tassi d’arrivo.
         */
        double currentTime = event.getTime();
        if (currentTime>1800 ){
            for(int i = 0 ; i < casse.serverStatus.length; i++){
                if (listBloccati.contains(casse.occupant[i])){
                    casse.markServerIdle(i,casse.occupant[i],"standard","forzato",t.current);
                }
            }
        }
        listBloccati.removeAll(listBloccati);
        for(int i =0 ; i < casse.serverStatus.length; i++){
            listBloccati.add(casse.occupant[i]);
        }

//        System.out.println();
//        System.out.printf("=== INVENTORY_TICK alle ore %.2f ===%n", currentTime / 3600.0);
//        System.out.println();

        // 1) Controllo scorte e generazione ordini — OGNI ORA (coerente con run/finito): il tick
        //    gira ogni 30 min (stats inventario) ma il riordino è orario.
        if (currentTime % reviewSeconds == 0) {
            List<Order> newOrders = inventorySystem.checkAndCreateOrders();
            for (int i = 0; i < newOrders.size(); i++) {
                dispatchReorderToMagazziniere(newOrders.get(i)); // spezza in ordini ≤ 20 unità
            }
        }

        // 2) Pianifica il prossimo tick a +30 minuti
        double nextTickTime = currentTime + INVENTORY_SLOT_SECONDS;

        // Se STOP è infinito (orizzonte infinito) pianifica sempre.
        // Se STOP è finito, pianifica solo se non superi STOP.
        if (nextTickTime <= STOP_INFINITE) {
            Event nextTick = new Event(EventType.TIMECHANGE, nextTickTime);
            events.add(nextTick);
           // System.out.printf("  → Pianificato prossimo INVENTORY_TICK alle ore %.2f%n", nextTickTime / 3600.0);
        } else {
           // System.out.println("  → Nessun altro INVENTORY_TICK da pianificare (STOP raggiunto).");
        }

        //System.out.println("=".repeat(50));
    }

    /**
     * Invia un riordino al magazziniere SPEZZANDOLO in ordini ≤ MAX_REORDER_CHUNK unità.
     * L'inventario mantiene UN SOLO ordine LOGICO pendente per classe (currentOrderLevel = Q totale
     * impostato in placeOrder); le consegne dei singoli chunk (receiveDelivery) lo decrementano fino
     * a 0, dopodiché l'ordine logico risulta consegnato. Costi (order/setup) contati una volta sola
     * sull'ordine logico. Più chunk ⇒ il magazziniere (2 serventi) parallelizza e il braccio fa
     * carichi piccoli ⇒ delivery lag entro i 30 min e code più corte.
     */
    private void dispatchReorderToMagazziniere(Order order) {
        String classe = SimulationValues.getNameFromClasseId(order.getClassId());
        int remaining = order.getQuantity();
        while (remaining > 0) {
            int chunk = Math.min(MAX_REORDER_CHUNK, remaining);
            Event arrivalOrder = new Event(EventType.ARRIVAL, t.current);
            arrivalOrder.setId(--mId); // id univoco (negativo) per ciascun chunk
            arrivalOrder.setCenter(magazziniere);
            arrivalOrder.setClasseFarmaco(classe);
            arrivalOrder.setNumeroFarmaciRichiesti(chunk);
            arrivalOrder.setMittente("inventory_system");
            arrivalOrder.setExternal(false);
            events.add(arrivalOrder);
            remaining -= chunk;
        }
    }

    private Event buildInventoryOrderArrival(Order order, double when) {
        /**
         * Crea un ARRIVAL verso il Magazziniere per evadere un ordine interno.
         * Mantiene coerenza con il resto del sistema (mittente, online/external, etc.).
         */
        Event arrivalOrder = new Event(EventType.ARRIVAL, when);

        arrivalOrder.setId((mId-1));
        arrivalOrder.setCenter(magazziniere);
        arrivalOrder.setClasseFarmaco(SimulationValues.getNameFromClasseId(order.getClassId()));
        arrivalOrder.setNumeroFarmaciRichiesti(order.getQuantity());
        arrivalOrder.setMittente("inventory_system");
        arrivalOrder.setExternal(false);

        return arrivalOrder;
    }

    /** Imposta λ∞ statico e abilita la modalità infinite-arrivals */
    public void setArrivalRateInf(double lambda) {
        if (lambda <= 0.0) {
            throw new IllegalArgumentException("λ∞ deve essere > 0");
        }
        this.lambdaInf = lambda;
        this.infiniteArrivalMode = true;
    }

    /** Verifica che λ∞ sia stato impostato */
    private void ensureInfiniteLambda() {
        if (!infiniteArrivalMode || lambdaInf <= 0.0) {
            throw new IllegalStateException("λ∞ non impostato: chiama prima setArrivalRateInf(lambda)");
        }
    }
    // SOSTITUISCE la generateInitialArrivals()
    private void generateInitialArrivals() {
        // Infinite horizon: NIENTE pre-generazione massiva -> schedulo SOLO il primo arrivo
        ensureInfiniteLambda();
        r.selectStream(StreamType.STREAM_ARRIVAL);
        if (!infiniteArrivalMode) return;

        double arrivalTime = t.current + v.exponential(1.0 / lambdaInf);
        Event arrival = createArrivalEventAtTime(arrivalTime);
        events.add(arrival);
        // NB: createArrivalEventAtTime registra già i farmaci e il job generato
        // Se vuoi coerenza con la tua versione precedente che incrementava due volte, NON aggiungere altro qui.
    }

    // SOSTITUISCE la  generateNextArrival()
    private void generateNextArrival() {
        ensureInfiniteLambda();
        r.selectStream(StreamType.STREAM_ARRIVAL);
        if (!infiniteArrivalMode) return;

        double nextArrivalTime = t.current + v.exponential(1.0 / lambdaInf);
        Event arrival = createArrivalEventAtTime(nextArrivalTime);
        events.add(arrival);
        // NB: come sopra, niente globalStatistics.registerGeneratedJob() extra:
        // createArrivalEventAtTime() già fa registerGeneratedJob() e registerFarmaciRichiesti().
    }

    private void printSimpleStatistics(double[] data, String label) {
        if (data == null || data.length == 0) return;

        double sum = 0;
        for (double v : data) sum += v;
        double mean = sum / data.length;

        double ss = 0;
        for (double v : data) ss += (v - mean) * (v - mean);
        double variance = (data.length > 1) ? (ss / (data.length - 1)) : 0.0;
        double stdDev = Math.sqrt(variance);

        double tValue = 1.96; // approx per n grande
        double marginError = tValue * stdDev / Math.sqrt(Math.max(1, data.length));

        double lower = mean - marginError;
        double upper = mean + marginError;

        // clamp a 0 le metriche intrinsecamente non negative
        if (isNonNegativeMetric(label) && lower < 0) lower = 0;

        System.out.printf("%s = %.6f ± %.6f [%.6f, %.6f]%n",
                label, mean, marginError, lower, upper);
    }

    private boolean isNonNegativeMetric(String label) {
        if (label == null) return false;
        String l = label.toLowerCase(Locale.ROOT);
        // Metriche non-negative per costruzione (nomi standard: Response/Wait/Node/Queue/Utilization).
        return l.contains("response")
                || l.contains("wait")
                || l.contains("delay")
                || l.contains("node")
                || l.contains("queue")
                || l.contains("utilization")
                || l.contains("service");
    }

    private void resetBatchStatistics() {
        // Nota: currentTime è il tempo corrente della simulazione al momento del reset.
        // Non viene usato direttamente qui — i centri mantengono i propri timestamp interni.

        // ── ANTI-LEAK (resetBatchStatistics è chiamato SOLO dalla run infinita) ────────────────
        // Liste "registra-tutto" che crescono col numero TOTALE di job → si svuotano a ogni batch
        // (le statistiche di batch vengono dalle AREE integrate, non da queste liste).
        if (listSystemResponse != null) listSystemResponse.clear();
        if (inventorySystem != null) inventorySystem.clearCompletedOrders();

        for (Center center : centers) {
            if (center == null) continue;

            // ── Reset aree PASTA ────────────────────────────────────────────────
            // CassaOnline è esclusa: il reset della sua area avviene in resetLastUpdateTime()
            // insieme al batchStartTime, così il denominatore di avgNode/avgQueue
            // è la durata del singolo batch e non il tempo cumulativo totale.
            if (!(center instanceof CassaOnline)) {
                for (int i = 0; i < center.area.length; i++) {
                    center.area[i].node    = 0;
                    center.area[i].queue   = 0;
                    center.area[i].service = 0;
                }
            }

            // ── Reset contatori server ───────────────────────────────────────────
            for (Server s : center.servers) {
                s.service = 0;
                s.served  = 0;
            }

            // ── Reset contatori statistici base ──────────────────────────────────
            center.completedJobs = 0;
            center.arrivedJob    = 0;

            // ── Reset specifico per tipo ─────────────────────────────────────────
            // REGOLA: non toccare MAI firstArrive, lastArrive, lastDeparture,
            //         lastUpdateTime, numJobs, numBusyServers, serverBusy,
            //         waitingQueue, arrivalTimes (job in volo).
            // Questi rappresentano lo STATO OPERATIVO corrente del centro,
            // non le statistiche del batch. Azzerarli causa RT=0 o RT enormi.

            if (center instanceof MssqCenter mssq) {
                // MssqCenter usa firstArrive=-1 come flag "non inizializzato"
                mssq.firstArrive   = -1;
                mssq.lastArrive    = 0;
                mssq.lastDeparture = 0;
                mssq.resetBusyAreas();
                // Le liste cumulative devono essere svuotate: ogni batch deve produrre
                // un campione indipendente, non la media cumulativa di tutti i batch.
                mssq.waitingTimes.clear();
                mssq.responseTimes.clear();
            }
            else if (center instanceof CassaOnline co) {
                co.clearBatchData(); // resetta solo le liste interne, non i timestamp
            }
            else if (center instanceof Dispatcher d) {
                d.clearBatchData();
            }
            else if (center instanceof BraccioDue braccio) {
                braccio.clearBatchData();
            }
            else if (center instanceof PriorityQueueCenter pq) {
                pq.completedHighPriority = 0;
                pq.completedLowPriority  = 0;
                pq.busyHigh = 0.0;   // reset tempo busy per priorità (utilizzazione per-batch)
                pq.busyLow  = 0.0;
                pq.area[0].node = 0; pq.area[0].queue = 0;   // area per-priorità per-batch (era cumulativa)
                pq.area[1].node = 0; pq.area[1].queue = 0;
            }
            // Reset lastUpdateTime al tempo corrente: passare 0 causerebbe un delta enorme
            // (t_current - 0) al primo evento successivo, gonfiando area[0].node/queue e
            // producendo RT/Nq falsamente elevati (bug osservato su BraccioDue: RT=5359s).
            if (center instanceof CassaOnline co) {
                co.resetLastUpdateTime(t.current);
            } else if (center instanceof Dispatcher d) {
                d.resetLastUpdateTime(t.current);
            } else if (center instanceof BraccioDue braccio) {
                braccio.resetLastUpdateTime(t.current);
            } else if (center instanceof PriorityQueueCenter pq) {
                pq.resetLastUpdateTime(t.current);
            }

        }

        // ── Reset statistiche globali ─────────────────────────────────────────
        if (globalStatistics != null) {
            globalStatistics.totalSuccessfulExits       = 0;
            globalStatistics.totalReneging              = 0;
            globalStatistics.totalOutOfStock            = 0;
            globalStatistics.totalGeneratedJob          = 0;
            globalStatistics.totalGeneratedFarmaci      = 0;
            globalStatistics.totalSuccessfulExitsBracci = 0;
            // FIX 2: reset completions usati come denominatori in generateStatistics
            globalStatistics.cassaCompletions           = 0;
            globalStatistics.cassaOnlineCompletions     = 0;
            globalStatistics.dispatcherCompletions      = 0;
            globalStatistics.magazziniereCompletions    = 0;
            globalStatistics.braccioUnoCompletions      = 0;
            globalStatistics.braccioDueCompletions      = 0;
            globalStatistics.highPriorityCompletions    = 0;
            globalStatistics.lowPriorityCompletions     = 0;
            globalStatistics.outOfStockBraccioUno = 0;
            globalStatistics.farmaciVenduti             = 0;
            globalStatistics.farmaciCaricati            = 0;
        }

        // ── Reset contatori locali Simulation ─────────────────────────────────
        arrivalsCasse       = 0;
        arrivalsCassaOnline = 0;
        leavingJob          = 0;
        farmaciPersi        = 0;

        // ── Reset liste response times per batch ──────────────────────────────
        if (listGlobalResponse       != null) listGlobalResponse.clear();
        if (listCasseResponse        != null) listCasseResponse.clear();
        if (listCasseOnlineResponse  != null) listCasseOnlineResponse.clear();
        if (listDispatcherResponse   != null) listDispatcherResponse.clear();
        if (listBraccio1Response     != null) listBraccio1Response.clear();
        if (listBraccio2Response     != null) listBraccio2Response.clear();
        if (listMagazziniereResponse != null) listMagazziniereResponse.clear();
        if (listPagamentoResponse    != null) listPagamentoResponse.clear();

    }

    private static double meanSafe(java.util.List<Double> xs) {
        if (xs == null || xs.isEmpty()) return 0.0;
        double s = 0.0;
        for (double v : xs) s += v;
        return s / xs.size();
    }

    /** Tempo medio di servizio di un centro MssqCenter (service totale / job serviti sui server). */
    private static double avgServiceMssq(MssqCenter c) {
        double svc = 0.0; long n = 0;
        for (Server s : c.servers) { svc += s.service; n += s.served; }
        return (n > 0) ? svc / n : 0.0;
    }

    private void generateStatistics(Statistics[][] matrix, int batchIndex, int processedJobs, double firstArrivalTime, double lastDepartureTime) {
        double batchDuration = (firstArrivalTime >= 0) ? t.current - firstArrivalTime : t.current;
        if (batchDuration <= 0) batchDuration = 1.0;

        for (int iCenter = 0; iCenter < centers.length; iCenter++) {
            Center center = centers[iCenter];

            int numServers = (center instanceof MssqCenter || center instanceof Casse || center instanceof Magazziniere)
                    ? center.servers.size() : 1;
            Statistics s = new Statistics(numServers);

            if (center instanceof PriorityQueueCenter) {
                PriorityQueueCenter pqc = (PriorityQueueCenter) center;
                s.avgNode[0]  = (pqc.area[0].node + pqc.area[1].node)  / batchDuration;
                s.avgQueue[0] = (pqc.area[0].queue + pqc.area[1].queue) / batchDuration;
                int completedHigh = globalStatistics.highPriorityCompletions;
                int completedLow  = globalStatistics.lowPriorityCompletions;
                if (completedHigh + completedLow > 0) {
                    s.avgWait[0]  = (pqc.getAvgWait(PriorityQueueCenter.HIGH_PRIORITY,  globalStatistics) * completedHigh
                            + pqc.getAvgWait(PriorityQueueCenter.LOW_PRIORITY,   globalStatistics) * completedLow)
                            / (completedHigh + completedLow);
                    s.avgDelay[0] = (pqc.getAvgDelay(PriorityQueueCenter.HIGH_PRIORITY, globalStatistics) * completedHigh
                            + pqc.getAvgDelay(PriorityQueueCenter.LOW_PRIORITY,  globalStatistics) * completedLow)
                            / (completedHigh + completedLow);
                }
                // Utilizzazione dal TEMPO BUSY integrato (ρ ≤ 1; niente artefatto di accredito a inizio
                // servizio dei carichi compound). Split per coda: HIGH = carico, LOW = prelievi.
                if (batchDuration > 0) {
                    s.utilHigh = Math.min(1.0, pqc.busyHigh / batchDuration);
                    s.utilLow  = Math.min(1.0, pqc.busyLow  / batchDuration);
                    s.utilization[0] = Math.min(1.0, (pqc.busyHigh + pqc.busyLow) / batchDuration);
                }

            } else if (center instanceof MssqCenter) {
                MssqCenter mc = (MssqCenter) center;
                s.avgNode[0]  = mc.area[0].node  / batchDuration;
                s.avgQueue[0] = mc.area[0].queue / batchDuration;
                if (mc instanceof Casse) {
                    // ✅ FIX: risposta del NODO cassa (area/completati), NON l'end-to-end cliente.
                    // Prima usava listGlobalResponse (intero percorso) → (1) Casse_Response incoerente
                    // con E[Nq] via Little, (2) doppio conteggio nella risposta di sistema (wIngresso
                    // già conteneva bracci+pagamento). Ora coerente con CassaOnline (nodo) e con gli
                    // altri centri; il tempo ai bracci entra una sola volta nel sys via wBracci.
                    int den = mc.completedJobs;
                    s.avgWait[0]  = (den > 0) ? mc.area[0].node  / den : 0;   // W nodo cassa (coda+servizio)
                    s.avgDelay[0] = (den > 0) ? mc.area[0].queue / den : 0;   // attesa in coda cassa
                    // Tasso di abbandono per questo batch: abbandoni / arrivi
                    // globalStatistics.totalReneging e casse.arrivedJob sono già per-batch (resettati in resetBatchStatistics)
                    s.abandonmentRate = (casse.arrivedJob > 0)
                            ? (double) globalStatistics.totalReneging / casse.arrivedJob
                            : 0.0;
                } else if (mc instanceof Pagamento) {
                    // CassePagamento: W = area/completati al centro (completedJobs è per-batch).
                    int den = mc.completedJobs;
                    s.avgWait[0]  = (den > 0) ? mc.area[0].node  / den : 0;
                    s.avgDelay[0] = (den > 0) ? mc.area[0].queue / den : 0;
                } else {
                    // Magazziniere
                    int den = globalStatistics.magazziniereCompletions;
                    s.avgWait[0]  = (den > 0) ? mc.area[0].node  / den : 0;
                    s.avgDelay[0] = (den > 0) ? mc.area[0].queue / den : 0;
                }
                for (int i = 0; i < numServers; i++) {
                    s.utilization[i] = mc.getBusyAreaPerServer(i) / batchDuration;
                }

            } else if (center instanceof CassaOnline) {
                s.avgNode[0]  = center.area[0].node  / batchDuration;
                s.avgQueue[0] = center.area[0].queue / batchDuration;
                // Stesso problema delle Casse: area non include il tempo ai bracci.
                // listCasseOnlineResponse = tempo da arrivo CassaOnline a DEPARTURE CassaOnline.
                // cassaOnline.waitingTimes = tempo in coda alla cassa online.
                s.avgWait[0]  = meanSafe(listCasseOnlineResponse);
                s.avgDelay[0] = meanSafe(new ArrayList<>(cassaOnline.waitingTimes));
                s.utilization[0] = center.servers.get(0).service / batchDuration;

            } else {
                // Dispatcher, BraccioDue, altri centri semplici
                s.avgNode[0]  = center.area[0].node  / batchDuration;
                s.avgQueue[0] = center.area[0].queue / batchDuration;
                if (center.completedJobs > 0) {
                    s.avgWait[0]  = center.area[0].node  / center.completedJobs;
                    s.avgDelay[0] = center.area[0].queue / center.completedJobs;
                }
                // BraccioDue: servizio compound (K prelievi) → usa il TEMPO BUSY integrato (ρ ≤ 1),
                // non service/batchDuration (che accredita il blocco a inizio servizio → ρ>1).
                if (center instanceof BraccioDue bd) {
                    s.utilization[0] = (batchDuration > 0) ? Math.min(1.0, bd.busyTime / batchDuration) : 0.0;
                } else {
                    s.utilization[0] = center.servers.get(0).service / batchDuration;
                }
            }

            s.avgService[0] = center.getAvgService(0);
            matrix[iCenter][batchIndex] = s;
        }

        // Riga Sistema — W_system come somma pesata dei W per centro.
        // Un job va a BraccioUno OPPURE a BraccioDue, non ad entrambi:
        // bisogna ponderare i loro W sul numero di completamenti rispettivi,
        // esattamente come fa FiniteHorizonSimulation.generateSamplingEstimate().
        Statistics sys = new Statistics(1);

        // 1) W medio all'ingresso (Casse fisiche + CassaOnline), pesato su completamenti
        double nCasse  = globalStatistics.cassaCompletions;
        double nOnline = globalStatistics.cassaOnlineCompletions;
        Statistics sCasse  = matrix[0][batchIndex];
        Statistics sOnline = matrix[1][batchIndex];
        double wIngresso = (nCasse + nOnline > 0)
                ? (sCasse.avgWait[0] * nCasse + sOnline.avgWait[0] * nOnline) / (nCasse + nOnline)
                : 0.0;

        // 2) W al Dispatcher (tutti i job ci passano)
        Statistics sDisp = matrix[2][batchIndex];
        double wDispatcher = sDisp.avgWait[0];

        // 3) W ai Bracci — pesato su completamenti LOW-priority (clienti) vs BraccioDue
        double nB1 = globalStatistics.lowPriorityCompletions;
        double nB2 = globalStatistics.braccioDueCompletions;
        PriorityQueueCenter pqc = (PriorityQueueCenter) centers[3];
        double wB1 = pqc.getAvgWait(PriorityQueueCenter.LOW_PRIORITY, globalStatistics);
        Statistics sB2 = matrix[4][batchIndex];
        double wB2 = sB2.avgWait[0];
        double wBracci = (nB1 + nB2 > 0)
                ? (wB1 * nB1 + wB2 * nB2) / (nB1 + nB2)
                : 0.0;

        // 4) W a CassePagamento (solo clienti FISICI; gli online lo saltano)
        Statistics sPag = matrix[6][batchIndex];
        double wPagamento = (sPag != null) ? sPag.avgWait[0] : 0.0;

        sys.avgWait[0] = wIngresso + wDispatcher + wBracci + wPagamento;

        // avgNode: usa ancora l'area (Little's Law), ma senza Magazziniere e
        // solo LOW-priority di BraccioUno
        double totalArea = getAreaSystem();
        sys.avgNode[0] = totalArea / batchDuration;

        matrix[centers.length][batchIndex] = sys;
    }

    private void generateEstimate(Statistics[][] matrix, int numBatches) {
        System.out.println("\n=== ANALISI STATISTICA INFINITE HORIZON ===");

        for (int iCenter = 0; iCenter < centers.length; iCenter++) {
            Center center = centers[iCenter];
            System.out.println("");
            System.out.println("=========== For " + center.name + ": ===================");
            System.out.println("");
            double[] avgWait = new double[numBatches];
            double[] avgDelay = new double[numBatches];
            double[] avgNode = new double[numBatches];
            double[] avgQueue = new double[numBatches];
            double[] utilization = new double[numBatches];

            for (int b = 0; b < numBatches; b++) {
                Statistics s = matrix[iCenter][b]; // ✅ indice per posizione
                avgWait[b] = (s != null && s.avgWait != null && s.avgWait.length > 0) ? s.avgWait[0] : 0.0;
                avgDelay[b] = (s != null && s.avgDelay != null && s.avgDelay.length > 0) ? s.avgDelay[0] : 0.0;
                avgNode[b] = (s != null && s.avgNode != null && s.avgNode.length > 0) ? s.avgNode[0] : 0.0;
                avgQueue[b] = (s != null && s.avgQueue != null && s.avgQueue.length > 0) ? s.avgQueue[0] : 0.0;
                if (!(center instanceof MssqCenter)){
                    utilization[b] = s.utilization[0];
                }
            }

            printSimpleStatistics(avgWait,  "Response Time: ...... ");
            printSimpleStatistics(avgDelay, "Waiting Time: ....... ");
            printSimpleStatistics(avgNode,  "Avg Jobs in Node: ... ");
            printSimpleStatistics(avgQueue, "Avg Jobs in Queue: .. ");

            // Tempo medio di servizio (verifica servizio compound k·μ ai bracci: ≈ E[K]·15s)
            double[] avgService = new double[numBatches];
            for (int b = 0; b < numBatches; b++) {
                Statistics s = matrix[iCenter][b];
                avgService[b] = (s != null && s.avgService != null && s.avgService.length > 0) ? s.avgService[0] : 0.0;
            }
            printSimpleStatistics(avgService, "Avg Service Time: ... ");

            if (center instanceof MssqCenter) {
                // stampa per-server: calcola quanti slot max compaiono
                int maxSlots = 0;
                for (int b = 0; b < numBatches; b++) {
                    Statistics s = matrix[iCenter][b];
                    if (s != null && s.utilization != null) {
                        maxSlots = Math.max(maxSlots, s.utilization.length);
                    }
                }
                int limit = Math.max(maxSlots, center.servers.size());

                for (int physI = 0; physI < limit; physI++) {

                    for (int b = 0; b < numBatches; b++) {
                        Statistics s = matrix[iCenter][b];
                        utilization[b] = (s != null && s.utilization != null && physI < s.utilization.length)
                                ? s.utilization[physI]
                                : 0.0;
                    }
                    System.out.println("Server " + physI + ":");
                    printSimpleStatistics(utilization, "  Utilization: ...... ");
                }

                // Tasso di abbandono (solo Casse fisiche)
                if (center instanceof Casse) {
                    double[] abandonRate = new double[numBatches];
                    for (int b = 0; b < numBatches; b++) {
                        Statistics s = matrix[iCenter][b];
                        abandonRate[b] = (s != null) ? s.abandonmentRate : 0.0;
                    }
                    printSimpleStatistics(abandonRate, "Abandon Rate: ....... ");
                }
            }
            else {
                printSimpleStatistics(utilization, "Utilization: ......... ");
            }
            // ========= AUTOCORRELAZIONE TRA BATCH =========
           // System.out.println("  autocorrelation between batches (avgWait):");

             // ----- PER CENTRO -----
            String acFile = center.name + "_autocorrelation.dat";
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.File(acFile))) {
                for (double v : avgWait) pw.println(v);
            } catch (Exception e) {
                System.err.println("Errore scrittura " + acFile + ": " + e.getMessage());
            }

            // 1) stampa tabellina autocorrelazione (mean, stdev, r[1]…)
            try {
                Acs.autocorrelation(acFile);
            } catch (Exception e) {
                System.out.println("  Autocorrelation failed: " + e.getMessage());
            }

            // 2) controllo Chatfield con soglia (stampa PASS/FAIL)
            try {
                Acs.Result r = Acs.checkChatfield(acFile);
                if (!r.pass) {
                    System.out.printf("  → Aumenta batch size: serve |r1| < %.3f (ora %.3f)%n",
                            r.threshold, Math.abs(r.r1));
                }
            } catch (Exception e) {
                System.out.println("  Chatfield failed: " + e.getMessage());
            }

            // cleanup file centro
            try {
                new java.io.File(acFile).delete();
            } catch (Exception ignore) {
            }
        }

        // ── RIEPILOGO ρ PER CENTRO + MAX (controllo saturazione: nessun ρ > 0.95) ──
        // Per i centri multi-server stampa il MAX tra i server (il più carico = quello che satura).
        System.out.println("\n=== UTILIZZAZIONE rho PER CENTRO (media sui batch; multi-server = max server) ===");
        double rhoMax = 0.0; String rhoMaxName = "";
        for (int iC = 0; iC < centers.length; iC++) {
            double rhoCenter = computeCenterUtilization(matrix, iC, numBatches);
            // Braccio Uno: mostra anche lo split per coda (carico HIGH / prelievi LOW).
            String extra = "";
            if (centers[iC] instanceof PriorityQueueCenter) {
                double sh = 0, sl = 0; int c = 0;
                for (int b = 0; b < numBatches; b++) {
                    Statistics s = matrix[iC][b];
                    if (s != null) { sh += s.utilHigh; sl += s.utilLow; c++; }
                }
                if (c > 0) extra = String.format(Locale.US, "   (carico=%.4f, prelievi=%.4f)", sh / c, sl / c);
            }
            System.out.printf("  %-16s rho = %.4f%s%s%n", centers[iC].name, rhoCenter,
                    rhoCenter > 0.95 ? "   <-- > 0.95" : "", extra);
            if (rhoCenter > rhoMax) { rhoMax = rhoCenter; rhoMaxName = centers[iC].name; }
        }
        System.out.printf("  => rho_MAX = %.4f (%s)%n", rhoMax, rhoMaxName);

        // ----- GLOBALE -----
        System.out.println("");
        System.out.println("=========== Global Autocorrelation: ===================");
        System.out.println("");
        double[] gWait = new double[numBatches];
        for (int b = 0; b < numBatches; b++) {
            // ultima riga = sistema globale (come nel tuo codice)
            Statistics s = matrix[matrix.length - 1][b];
            gWait[b] = (s != null && s.avgWait != null && s.avgWait.length > 0) ? s.avgWait[0] : 0.0;
        }

        String gFile = "global_autocorrelation.dat";
        try (java.io.PrintWriter pg = new java.io.PrintWriter(new java.io.File(gFile))) {
            for (double g : gWait) pg.println(g);
        } catch (Exception e) {
            System.err.println("Errore scrittura " + gFile + ": " + e.getMessage());
        }

        // 1) stampa tabellina autocorrelazione globale
        try {
            Acs.autocorrelation(gFile);
        } catch (Exception e) {
            System.out.println("  Autocorrelation (GLOBAL) failed: " + e.getMessage());
        }

        // 2) controllo Chatfield globale
        try {
            Acs.Result gr = Acs.checkChatfield(gFile);
            if (!gr.pass) {
                System.out.printf("  → [GLOBAL] Aumenta batch size: serve |r1| < %.3f (ora %.3f)%n",
                        gr.threshold, Math.abs(gr.r1));
            }
        } catch (Exception e) {
            System.out.println("  Chatfield (GLOBAL) failed: " + e.getMessage());
        }

        // cleanup file globale
        try {
            new java.io.File(gFile).delete();
        } catch (Exception ignore) {
        }
    }

    private void writeBatchDatFiles(Statistics[][] batchStats, double[] batchLossProbs, int numBatches) {
        // -Dstats.dir per instradare gli output nell'attività corrente del piano esperimenti.
        String dir = System.getProperty("stats.dir", "stats/infinite");
        new java.io.File(dir).mkdirs();

        String[] centerNames = {"Casse", "CassaOnline", "Dispatcher", "BraccioUno", "BraccioDue", "Magazziniere", "Pagamento"};

        for (int c = 0; c < centers.length; c++) {
            String nm = centerNames[c];
            double[] rt   = new double[numBatches];
            double[] util = new double[numBatches];
            double[] nq   = new double[numBatches];

            for (int b = 0; b < numBatches; b++) {
                Statistics s = batchStats[c][b];
                if (s == null) continue;
                if (s.avgWait     != null && s.avgWait.length     > 0) rt[b]   = s.avgWait[0];
                if (s.avgQueue    != null && s.avgQueue.length    > 0) nq[b]   = s.avgQueue[0];
                if (s.utilization != null && s.utilization.length > 0) {
                    double sum = 0;
                    for (double u : s.utilization) sum += u;
                    util[b] = sum / s.utilization.length;
                }
            }

            writeDat(dir + "/infinite_" + nm + "_Response.dat",    rt);
            writeDat(dir + "/infinite_" + nm + "_Utilization.dat", util);
            writeDat(dir + "/infinite_" + nm + "_Queue.dat",       nq);
        }

        double[] sysRT = new double[numBatches];
        for (int b = 0; b < numBatches; b++) {
            Statistics sys = batchStats[centers.length][b];
            if (sys != null && sys.avgWait != null && sys.avgWait.length > 0)
                sysRT[b] = sys.avgWait[0];
        }
        writeDat(dir + "/infinite_system_Response.dat", sysRT);
        writeDat(dir + "/infinite_system_lossProb.dat",     batchLossProbs);
    }

    private void writeDat(String path, double[] values) {
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(path))) {
            pw.println("# batch\tvalue");
            for (int i = 0; i < values.length; i++)
                pw.printf(java.util.Locale.US, "%d\t%.8f%n", i + 1, values[i]);
        } catch (java.io.IOException e) {
            System.err.println("Errore scrittura " + path + ": " + e.getMessage());
        }
    }

    private void generateSlotChange() {
        // Genera eventi di cambio slot ogni 1800 secondi (30 minuti).
        // IMPORTANTE: +0.001s per garantire che SLOTCHANGE scatti DOPO il SAMPLING
        // dello stesso istante. Il comparator della PQ ordina per ordinal enum (SLOTCHANGE=4
        // < SAMPLING=5), quindi senza l'offset il cambio di configurazione (nActive) avviene
        // PRIMA della raccolta statistiche, facendo risultare utilization > 1 quando i server
        // vengono ridotti nello slot successivo.
        for (int slot = 1; slot < 24; slot++) { // 24 slot da 30 minuti = 12 ore
            double slotChangeTime = slot * 1800.0 + 0.001;
            if (slotChangeTime <= STOP) {
                events.add(new Event(EventType.SLOTCHANGE, slotChangeTime));
            }
        }
    }

    private void changeConfigurationCenters(int[] newConfig) {
        // newConfig[0] = numero casse fisiche
        // newConfig[1] = numero magazzinieri
        updateCenterServers(casse, newConfig[0]);
        updateCenterServers(magazziniere, newConfig[1]);
    }

    private void updateCenterServers(Center center, int newNumServers) {

        if (center instanceof  Casse) {
            currentServers = casse.serverStatus.length;
            for (int i = 0; i < casse.serverStatus.length; i++) {
                if (casse.serverStatus[i] == -1 && casse.occupant[i] == -1) {
                    currentServers--;
                }
            }
        }
        if (center instanceof  Magazziniere) {
            currentServers = magazziniere.serverStatus.length;
            for (int i = 0; i < magazziniere.serverStatus.length; i++) {
                if (magazziniere.serverStatus[i] == -1 && magazziniere.occupant[i] == -1) {
                    currentServers--;
                }
            }
        }
        if (newNumServers > currentServers) {
            // Aggiungi server
            if (center instanceof Casse){
                casse.changeConfiguration("add",newNumServers-currentServers,t.current);
            }
            if (center instanceof Magazziniere){
                magazziniere.changeConfiguration("add",newNumServers-currentServers,t.current);
            }
        } else if (newNumServers < currentServers) {

            if (center instanceof Casse){
                casse.changeConfiguration("remove",currentServers-newNumServers,t.current);
            }
            if (center instanceof Magazziniere){
                magazziniere.changeConfiguration("remove",currentServers-newNumServers,t.current);
            }
        }
    }

    /**
     * Raccoglie le statistiche per la finestra di campionamento corrente.
     * Segue la stessa logica del progetto di riferimento: i contatori sono stati
     * azzerati all'inizio della finestra da resetStatistics(), quindi le statistiche
     * cumulative correnti corrispondono alle statistiche della sola finestra.
     * T_window = currentTime - snapTime è la durata della finestra.
     */
    private void generateSamplingStatistics(int samplingIndex, double currentTime) {
        double T = currentTime - snapTime;  // durata della finestra corrente

        for (int ci = 0; ci < centers.length; ci++) {
            Center center = centers[ci];
            Statistics stats = new Statistics(1);

            if (center instanceof PriorityQueueCenter pqc) {

                // E[N] e E[Nq]: integrale area / T_window (somma delle due priorità)
                if (T > 0) {
                    stats.avgNode[0]  = (pqc.area[0].node  + pqc.area[1].node)  / T;
                    stats.avgQueue[0] = (pqc.area[0].queue + pqc.area[1].queue) / T;
                }

                // E[T] e E[W]: area / completamenti, media ponderata per priorità
                int cHigh = globalStatistics.highPriorityCompletions + globalStatistics.outOfStockBraccioUno;
                int cLow  = globalStatistics.lowPriorityCompletions  + globalStatistics.outOfStockBraccioUno;
                double wH = (cHigh > 0) ? pqc.area[0].node  / cHigh : 0;
                double wL = (cLow  > 0) ? pqc.area[1].node  / cLow  : 0;
                double dH = (cHigh > 0) ? pqc.area[0].queue / cHigh : 0;
                double dL = (cLow  > 0) ? pqc.area[1].queue / cLow  : 0;
                int tot = cHigh + cLow;
                if (tot > 0) {
                    stats.avgWait[0]  = (wH * cHigh + wL * cLow) / tot;
                    stats.avgDelay[0] = (dH * cHigh + dL * cLow) / tot;
                }
                // Split per coda (HIGH=carico, LOW=prelievi) per i .dat per-coda del Braccio Uno.
                stats.avgWaitHigh = wH;  stats.avgWaitLow = wL;
                stats.avgDelayHigh = dH; stats.avgDelayLow = dL;
                if (T > 0) {
                    stats.avgNodeHigh  = pqc.area[0].node  / T;  stats.avgNodeLow  = pqc.area[1].node  / T;
                    stats.avgQueueHigh = pqc.area[0].queue / T;  stats.avgQueueLow = pqc.area[1].queue / T;
                }

                // Interarrivi: T_window / numero di arrivi nella finestra
                if (pqc.arrivedJob > 1 && T > 0) {
                    stats.avgInterarrivals[0] = T / pqc.arrivedJob;
                }

                // Utilizzazione per-finestra dal TEMPO BUSY integrato (ρ ≤ 1), split per coda:
                // HIGH = carico/rifornimento, LOW = prelievi cliente.
                if (T > 0) {
                    stats.utilHigh = Math.min(1.0, pqc.busyHigh / T);
                    stats.utilLow  = Math.min(1.0, pqc.busyLow  / T);
                    stats.utilization[0] = Math.min(1.0, (pqc.busyHigh + pqc.busyLow) / T);
                }

            } else {

                // E[N] e E[Nq]: integrale area / T_window
                if (T > 0) {
                    stats.avgNode[0]  = center.area[0].node  / T;
                    stats.avgQueue[0] = center.area[0].queue / T;
                }

                // E[T] e E[W] per-finestra tramite Little's law:
                //   area.node / throughput_window
                // Per MssqCenter il denominatore corretto è completedJobs (reset → locali alla finestra).
                // Per gli altri centri i contatori di completamento in globalStatistics sono già locali.
                if (center instanceof MssqCenter) {
                    // completedJobs è reset all'inizio della finestra → conta solo i job completati qui
                    int denom = center.completedJobs;
                    if (denom > 0) {
                        stats.avgWait[0]  = center.area[0].node  / denom;
                        stats.avgDelay[0] = center.area[0].queue / denom;
                    }
                } else {
                    stats.avgWait[0]  = center.getAvgWait(0, globalStatistics);
                    stats.avgDelay[0] = center.getAvgDelay(0, globalStatistics);
                }

                // Interarrivi: T_window / numero di arrivi nella finestra
                if (center.arrivedJob > 1 && T > 0) {
                    stats.avgInterarrivals[0] = T / center.arrivedJob;
                }

                // Utilizzazione per-finestra:
                // MssqCenter: utilizziamo ∫busyCount(t)dt = area[0].node - area[0].queue
                //   perché busyCount = numJobs - numInQueue = server effettivamente occupati
                //   (sia in scan che in attesa farmaci). Questo dà la vera ρ per server.
                // Single-server: server[0].service = tempo di servizio allocato questa finestra.
                if (center instanceof MssqCenter msq && T > 0) {
                    int nActive = 0;
                    for (int i = 0; i < center.servers.size(); i++) {
                        if (msq.serverStatus[i] != -1) nActive++;
                    }
                    if (nActive > 0) {
                        // ∫busyCount dt = ∫(numJobs - numInQueue) dt = area.node - area.queue
                        double busyServerSeconds = center.area[0].node - center.area[0].queue;
                        stats.utilization[0] = Math.min(1.0, busyServerSeconds / (T * nActive));
                    }
                } else if (center instanceof BraccioDue bd && T > 0) {
                    // Servizio compound (K prelievi) → TEMPO BUSY integrato (ρ ≤ 1), non service/T.
                    stats.utilization[0] = Math.min(1.0, bd.busyTime / T);
                } else if (T > 0) {
                    stats.utilization[0] = center.servers.get(0).service / T;
                }
            }

            // Probabilità di perdita per-finestra. Denominatori coerenti con l'unità del numeratore:
            // OOS è per-ARTICOLO (/ farmaci richiesti), abbandono è per-ORDINE (/ job). Sommarli sotto
            // lo stesso denominatore "job" gonfiava la stima (P(OOS) poteva superare 1).
            double oosP = (globalStatistics.totalGeneratedFarmaci > 0)
                    ? (double) globalStatistics.totalOutOfStock / globalStatistics.totalGeneratedFarmaci : 0.0;
            double abbP = (globalStatistics.totalGeneratedJob > 0)
                    ? (double) globalStatistics.totalReneging / globalStatistics.totalGeneratedJob : 0.0;
            stats.lossProbability = oosP + abbP;

            // Contatori della finestra (usati dall'aggregazione in FiniteHorizonSimulation)
            stats.totalSuccessfulExits   = globalStatistics.totalSuccessfulExits;
            stats.totalReneging          = globalStatistics.totalReneging;
            stats.totalOutOfStock        = globalStatistics.totalOutOfStock;
            stats.totalGeneratedJob      = globalStatistics.totalGeneratedJob;
            stats.cassaCompletions       = globalStatistics.cassaCompletions;
            stats.cassaOnlineCompletions = globalStatistics.cassaOnlineCompletions;
            stats.dispatcherCompletions  = globalStatistics.dispatcherCompletions;
            stats.lowPriorityCompletions = globalStatistics.lowPriorityCompletions;
            stats.braccioDueCompletions  = globalStatistics.braccioDueCompletions;
            stats.avgService[0]          = center.getAvgService(0); // tempo medio di servizio del centro

            matrix[ci][samplingIndex] = stats;
        }
    }

    private void resetStatistics() {
        // Reset SOLO delle variabili accumulative per le statistiche
        for (Center center : centers) {
            if (center.area != null) {
                for (Area area : center.area) {
                    if (area != null) {
                        area.node = 0;
                        area.queue = 0;
                        area.service = 0;
                    }
                }
            }

            // Reset contatori server (tempo di servizio cumulato e pezzi serviti)
            for (Server server : center.servers) {
                server.service = 0;
                server.served  = 0;
            }

            center.completedJobs = 0;

            // Reset specifici per i centri MSSQ (Casse, Magazziniere, ecc.)
            if (center instanceof MssqCenter mm) {
                // ✅ azzera l’integrazione del busy per-server per il prossimo batch
                mm.resetBusyAreas();

                // ✅ riallinea i riferimenti temporali del centro (usati per N(t), Q(t), utilizzo)
                mm.firstArrive   = -1;
                mm.lastArrive    = 0;
                mm.lastDeparture = 0;
            }

            // (eventuali reset analoghi per altri tipi di centro, se necessari)
        }

        // Reset dei contatori di arrivi/tempo per statistiche per-finestra
        // + riallineamento del riferimento temporale per il calcolo del delta nell'area
        Event windowMarker = new Event(EventType.SAMPLING, snapTime);
        for (Center c : centers) {
            c.arrivedJob = 0;
            if (c instanceof PriorityQueueCenter pqc) {
                pqc.firstArriveHigh = 0;
                pqc.lastArriveHigh  = 0;
                pqc.firstArriveLow  = 0;
                pqc.lastArriveLow   = 0;
                pqc.lastDeparture   = 0;
                pqc.busyHigh = 0.0;   // reset tempo busy per priorità (utilizzo per-finestra)
                pqc.busyLow  = 0.0;
                pqc.resetLastUpdateTime(snapTime);
            } else if (c instanceof Dispatcher d) {
                d.resetLastUpdateTime(snapTime);
            } else if (c instanceof CassaOnline co) {
                co.resetLastUpdateTime(snapTime);
            } else if (c instanceof BraccioDue bd) {
                bd.busyTime = 0.0;    // reset tempo busy per-finestra
                bd.resetLastUpdateTime(snapTime);
            } else if (c instanceof MssqCenter) {
                // MssqCenter usa currentEvent.getTime() per il delta dell'area;
                // impostiamo un marker al tempo del reset così il prossimo delta parte da snapTime
                c.currentEvent = windowMarker;
            }
        }

        // Reset delle statistiche globali di finestra
        globalStatistics.cassaCompletions        = 0;
        globalStatistics.cassaOnlineCompletions  = 0;
        globalStatistics.dispatcherCompletions   = 0;
        globalStatistics.magazziniereCompletions = 0;
        globalStatistics.braccioUnoCompletions   = 0;
        globalStatistics.braccioDueCompletions   = 0;
        globalStatistics.highPriorityCompletions = 0;
        globalStatistics.lowPriorityCompletions  = 0;
        globalStatistics.outOfStockBraccioUno    = 0;
        globalStatistics.totalSuccessfulExits    = 0;
        globalStatistics.totalReneging           = 0;
        globalStatistics.totalOutOfStock         = 0;
        globalStatistics.totalGeneratedJob       = 0;
        // NOTA: farmaciVenduti/farmaciCaricati NON sono azzerati per-finestra: vogliono restare
        // conteggi cumulati di replica (come totalGeneratedFarmaci), così il riepilogo [REPLICA]
        // confronta grandezze omogenee (giornaliere).

        // CRITICO: NON resettare le strutture operative
    }

    private void logJobEvent(String eventType, Event event, Center center, String details) {
        Event infoEvent = event;

        String farmaco = infoEvent.getClasseFarmaco();
        Integer qty = infoEvent.getNumeroFarmaciRichiesti();
        Integer jobId = infoEvent.getId();
        String mittente = infoEvent.getMittente();
        boolean isOnline = infoEvent.checkOnline();
        String centerName = center.name;

        // Speciale gestione per le Casse
        if (center instanceof Casse && eventType.equals("ARRIVAL")) {
            if (details.contains("SERVIZIO_")) {
                String serverIndex = details.substring(details.indexOf("SERVIZIO_") + 9);
                centerName = center.name ;
            }
        } else if (center instanceof Casse && eventType.equals("DEPARTURE")) {
            Server s = event.getServer();
            if (s != null) {
                centerName = center.name + " [Server " + s.id + "]";
            } else {
                System.err.printf("[WARN] logJobEvent: server nullo per JOB_%d nel centro %s%n",
                        jobId != null ? jobId : -1, center.name);
                centerName = center.name + " [Server UNKNOWN]";
            }
        }
//        System.out.printf("[T=%.1f(%02d:%02dh)] JOB_%d: %s in %s | Mittente=%s | Farmaco=%s x%d | %s | isonline = %b%n",
//                t.current,
//                8 + (int) (t.current / 60) / 60,
//                (int) (t.current / 60) % 60,
//                jobId != null ? jobId : -1,
//                eventType,
//                centerName,
//                mittente != null ? mittente : "NULL",
//                farmaco != null ? farmaco : "NULL",
//                qty != null ? qty : 0,
//                details, isOnline);

    }

    private void printCentersStatistics() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("           STATISTICHE DEI CENTRI");
        System.out.println("=".repeat(60));

        System.out.println("\n--- CASSE FARMACIA ---");
        casse.printStatistics(globalStatistics);


        System.out.println("\n--- CASSA ONLINE ---");
        cassaOnline.printStatistics(globalStatistics);
        System.out.println("\n--- DISPATCHER ---");
        dispatcher.printStatistics(globalStatistics);

        System.out.println("\n--- BRACCIO UNO ---");
        braccioUno.printStatistics(globalStatistics);

        System.out.println("\n--- BRACCIO DUE ---");
        braccioDue.printStatistics(globalStatistics);

        System.out.println("\n--- CASSE PAGAMENTO ---");
        pagamento.printStatistics(globalStatistics);

        System.out.println("\n--- MAGAZZINIERE ---");
        magazziniere.printStatistics(globalStatistics);

    }

    private void printSystemStatistics() {
        int totalCompletedJobs = globalStatistics.totalSuccessfulExits;
        DecimalFormat f = new DecimalFormat("###0.000");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("         STATISTICHE DEL SISTEMA");
        System.out.println("=".repeat(60));

        if (totalCompletedJobs > 0) {
            System.out.println("\n  Numero di Clienti Serviti ............. = " + totalCompletedJobs+ " clienti" );

            // Tempo di risposta e popolazione del SISTEMA su TUTTI i centri attraversati dai
            // clienti (Casse, CassaOnline, Dispatcher, Braccio Uno LOW, Braccio Due, CassePagamento;
            // Magazziniere escluso = rifornimenti interni). getAreaSystem() = ∫ L(t) dt del sistema.
            // PRIMA qui si usava solo l'area delle casse → W di sistema enormemente sottostimato
            // (ignorava bracci e pagamento, dove il cliente passa la maggior parte del tempo).
            double clientArea = getAreaSystem();
            double avgSystemResponseTime = clientArea / totalCompletedJobs;

            System.out.println("  Tempo di Risposta Medio del Sistema ... = " +
                    f.format(avgSystemResponseTime) + " secondi");
            // Confronto con la misura diretta end-to-end (arrivo cassa → uscita finale dal sistema)
            System.out.println("  [check] Response time end-to-end medio  = " +
                    f.format(meanSafe(listGlobalResponse)) + " secondi");

            double systemTime = t.current;
            if (systemTime > 0) {
                double avgPopulation = clientArea / systemTime;
                System.out.println("  Numero Medio di Clienti nel Sistema ... = " +
                        f.format(avgPopulation));

                // ✅ VERIFICA LITTLE'S LAW: L = λ × W
                double arrivalRate = totalCompletedJobs / (systemTime / 3600.0); // clienti/ora
                double littleCheck = arrivalRate * (avgSystemResponseTime / 3600.0); // clienti
                System.out.println("  Verifica Little's Law (L = λ × W) ..... = " +
                        f.format(littleCheck) + " (dovrebbe ≈ " + f.format(avgPopulation) + ")");
            }

            System.out.println("  Throughput del Sistema ................ = " +
                    f.format(totalCompletedJobs / (t.current / 3600.0)) + " clienti/ora");

            if (globalStatistics.totalGeneratedJob > 0) {
                double successRate = (double) totalCompletedJobs / globalStatistics.totalGeneratedJob * 100;
                System.out.println("  Tasso di Successo ..................... = " +
                        f.format(successRate) + "%");

                double abandonRate = (double) globalStatistics.totalReneging / globalStatistics.totalGeneratedJob * 100;
                System.out.println("  Tasso di Abbandono .................... = " +
                        f.format(abandonRate) + " %");
            }

            if (globalStatistics.farmaciVenduti > 0) {
                System.out.println("  Farmaci Venduti Totali (articoli) ..... = " +
                        globalStatistics.farmaciVenduti);
                System.out.println("  Farmaci per Cliente Completato ........ = " +
                        f.format((double) globalStatistics.farmaciVenduti / totalCompletedJobs));
            }

        } else {
            System.out.println("Nessun cliente completato durante la simulazione.");
        }


    }

    private double getAreaSystem() {
        double areaSystem = 0;  // Area totale sottesa al grafico L(t) per l'intero sistema

        // Somma le aree dei soli centri attraversati dai job dei clienti.
        // Il Magazziniere è ESCLUSO: processa ordini di rifornimento interni,
        // i job dei clienti non vi transitano direttamente.
        for (Center center : centers) {
            if (center instanceof Magazziniere) {
                // Escluso: non è un centro cliente
                continue;
            }
            else if (center instanceof MssqCenter) {
                // Casse fisiche
                areaSystem += center.area[0].node;
            }
            else if (center instanceof CassaOnline) {
                areaSystem += center.area[0].node;
            }
            else if (center instanceof Dispatcher) {
                areaSystem += center.area[0].node;
            }
            else if (center instanceof PriorityQueueCenter) {
                // Braccio Uno: solo la coda LOW-priority (job clienti)
                // HIGH-priority sono i frammenti interni del Magazziniere
                PriorityQueueCenter priorityCenter = (PriorityQueueCenter) center;
                areaSystem += priorityCenter.area[1].node;  // LOW priority = clienti
            }
            else if (center instanceof BraccioDue) {
                areaSystem += center.area[0].node;
            }
        }

        return areaSystem;
    }

    public Event getEventFromId(int targetId) {
        for (Event event : events) {
            if (event.getId() == targetId) {
                return event;
            }
        }
        return null;
    }
// ===== ANALISI DEL TRANSITORIO =====

    /**
     * Esegue UNA singola replica per l'analisi del transitorio.
     * Chiamato da TransientAnalysis per ogni replica indipendente.
     *
     * Struttura identica a runInfiniteHorizonSimulation:
     * - arrivi on-the-fly Poisson(λ∞) via generateNextArrival()
     * - handleTimeChange con STOP_INFINITE (nessun loop infinito di TIMECHANGE)
     * - nessun warm-up, nessun batch: campiona dall'istante 0
     *
     * Ad ogni OBSERVATION_INTERVAL completamenti chiama samplingCallback
     * passando il contatore corrente. Il callback raccoglie le statistiche.
     * La replica termina dopo numObservations * observationInterval completamenti.
     *
     * @param numObservations     numero di punti da campionare
     * @param observationInterval ogni quanti completamenti campionare
     * @param inventory_setup     configurazione scorte {s1..s5}
     * @param samplingCallback    lambda chiamata ad ogni punto di campionamento
     */
    public void runTransientAnalysis(int numObservations,
                                     int observationInterval,
                                     int[] inventory_setup,
                                     java.util.function.IntConsumer samplingCallback) {

        int totalCompletions = numObservations * observationInterval;
        int completionCount  = 0;

        // ── Inizializzazione (identica a runInfiniteHorizonSimulation) ────────
        // NON chiama initGenerators(): i generatori arrivano dall'esterno via initSeed().
        resetNextId();
        initInventorySystem(
                inventory_setup[0], inventory_setup[1], inventory_setup[2],
                inventory_setup[3], inventory_setup[4]);
        initCenters(new int[]{5, 2});
        initEvents();
        t = new Time(0, 0);
        closeTheDoor = false;
        globalStatistics = new Statistics(1);
        this.notCompleted = new ArrayList<>();
        this.fakeAbandon  = new ArrayList<>();
        listGlobalResponse.clear();

        if (braccioUno instanceof PriorityQueueCenter) {
            ((PriorityQueueCenter) braccioUno).inventorySystem = inventorySystem;
        }
        braccioDue.inventorySystem = inventorySystem;

        // Avvia processo di arrivo Poisson(λ∞) e inventory tick
        ensureInfiniteLambda();
        generateInitialArrivals();
        generateInventoryTick();

        // ── Ciclo principale ─────────────────────────────────────────────────
        while (completionCount < totalCompletions) {

            Event event = events.poll();
            if (event == null) {
                generateNextArrival();
                continue;
            }

            t.current = event.getTime();
            inventorySystem.updateTime(t.current);

            // Micro-evento di carico incrementale: deposita 1 unità a banco (SOLO inventario). Il tempo
            // è già avanzato da updateTime sopra → integrale holding corretto. Nessun centro/coda.
            if (event.getType() == EventType.RESTOCK) {
                inventorySystem.receiveDelivery(
                        SimulationValues.getClassIdFromName(event.getClasseFarmaco()), 1, t.current, event.getId());
                continue;
            }

            Center currentCenter = event.getCenter();
            if (currentCenter == null
                    && event.getType() != EventType.TIMECHANGE
                    && event.getType() != EventType.SAMPLING) {
                continue;
            }

            if (currentCenter != null) {
                currentCenter.updateStatistics(event);
            }

            switch (event.getType()) {

                case ARRIVAL:
                    currentCenter.currentEvent = event;
                    event.setFirstArrivalTime(t.current);

                    if (currentCenter instanceof Casse) {
                        arrivalTimeCasse.put(event.getId(), t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if (currentCenter instanceof CassaOnline) {
                        arrivalTimeCasseOnline.put(event.getId(), t.current);
                        globalStatistics.registerGeneratedJob();
                    }
                    if (currentCenter instanceof Dispatcher)  arrivalTimeDispatcher.put(event.getId(), t.current);
                    if (currentCenter instanceof BraccioUno)  arrivalTimeBraccio1.put(event.getId(), t.current);
                    if (currentCenter instanceof BraccioDue)  arrivalTimeBraccio2.put(event.getId(), t.current);
                    if (currentCenter instanceof Magazziniere) arrivalTimeMagazziniere.put(event.getId(), t.current);
                    if (currentCenter instanceof Pagamento)   arrivalTimePagamento.put(event.getId(), t.current);

                    int arrivalResult = currentCenter.processArrival();

                    // Abbandoni clienti fisici in coda
                    if (ABANDONMENT_ENABLED && event.isExternal() && arrivalResult == -1 && !event.checkOnline()) {
                        r.selectStream(StreamType.STREAM_P_ABANDON);
                        double rnd = r.random();
                        double pazienza;
                        if (rnd <= 0.03) {
                            pazienza = v.boundedNormal(60.0, 20.0, 30.0, 180.0);
                        } else if (rnd <= 0.2) {
                            pazienza = v.boundedNormal(200.0, 50.0, 180.0, 350.0);
                        } else {
                            pazienza = v.boundedNormal(400.0, 90.0, 350.0, 650.0);
                        }
                        Event abandon = new Event(EventType.ABANDON, t.current + pazienza);
                        abandon.setCenter(currentCenter);
                        abandon.setId(event.getId());
                        abandon.setClasseFarmaco(event.getClasseFarmaco());
                        abandon.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                        events.add(abandon);
                    }

                    // Genera DEPARTURE per job che entrano in servizio
                    if (currentCenter instanceof MssqCenter) {
                        if (arrivalResult >= 0 && arrivalResult < currentCenter.servers.size()) {
                            Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(arrivalResult));
                            if (departure != null) {
                                departure.setId(event.getId());
                                departure.setClasseFarmaco(event.getClasseFarmaco());
                                departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                                departure.setOnline(event.checkOnline());
                                departure.setExternal(event.isExternal());
                                departure.setMittente(event.getMittente());
                                departure.setOriginalCassaId(arrivalResult);
                                events.add(departure);
                            }
                        }
                    } else if (arrivalResult == 0) {
                        Event departure = generateDepartureEvent(currentCenter, currentCenter.getServer(0));
                        if (departure != null) {
                            departure.setId(event.getId());
                            departure.setClasseFarmaco(event.getClasseFarmaco());
                            departure.setNumeroFarmaciRichiesti(event.getNumeroFarmaciRichiesti());
                            departure.setOnline(event.checkOnline());
                            departure.setExternal(event.isExternal());
                            departure.setMittente(event.getMittente());
                            departure.setOriginalCassaId(
                                    currentCenter instanceof CassaOnline ? 0 : event.getOriginalCassaId());
                            events.add(departure);
                        }
                    }

                    if (event.isExternal()) {
                        generateNextArrival();
                    }
                    break;

                case DEPARTURE:
                    currentCenter.currentEvent = event;

                    int departedJobId = event.getId();

                    // Calcolo response time per centro con containsKey guard (evita NPE)
                    if (currentCenter instanceof Casse && arrivalTimeCasse.containsKey(departedJobId))
                        listCasseResponse.add(t.current - arrivalTimeCasse.get(departedJobId));
                    if (currentCenter instanceof CassaOnline && arrivalTimeCasseOnline.containsKey(departedJobId))
                        listCasseOnlineResponse.add(t.current - arrivalTimeCasseOnline.get(departedJobId));
                    if (currentCenter instanceof Dispatcher && arrivalTimeDispatcher.containsKey(departedJobId))
                        listDispatcherResponse.add(t.current - arrivalTimeDispatcher.get(departedJobId));
                    if (currentCenter instanceof BraccioUno && arrivalTimeBraccio1.containsKey(departedJobId))
                        listBraccio1Response.add(t.current - arrivalTimeBraccio1.get(departedJobId));
                    if (currentCenter instanceof BraccioDue && arrivalTimeBraccio2.containsKey(departedJobId))
                        listBraccio2Response.add(t.current - arrivalTimeBraccio2.get(departedJobId));
                    if (currentCenter instanceof Magazziniere && arrivalTimeMagazziniere.containsKey(departedJobId))
                        listMagazziniereResponse.add(t.current - arrivalTimeMagazziniere.get(departedJobId));
                    if (currentCenter instanceof Pagamento && arrivalTimePagamento.containsKey(departedJobId))
                        listPagamentoResponse.add(t.current - arrivalTimePagamento.get(departedJobId));

                    int departureResult = currentCenter.processDeparture();

                    if (currentCenter instanceof Magazziniere) {
                        handleMagazziniere(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Dispatcher) {
                        handleDispatcher(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Casse) {
                        handleCasse(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof Pagamento) {
                        handlePagamento(event, currentCenter, departureResult);
                        break;
                    }
                    if (currentCenter instanceof BraccioDue) {
                        handleBraccioDue(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof BraccioUno) {
                        handleBraccioUno(event, currentCenter, departureResult, departedJobId);
                        break;
                    }
                    if (currentCenter instanceof CassaOnline) {
                        handleCassaOnline(event, currentCenter, departureResult);
                        break;
                    }
                    break;

                case ABANDON:
                    handleAbandon(event);
                    break;

                case TIMECHANGE:
                    // Usa STOP_INFINITE: nessun loop infinito, solo tick inventario
                    handleTimeChange(event, STOP_INFINITE);
                    break;

                case COMPLETION:
                    handleCompletion(event);
                    completionCount++;

                    // Campiona ogni observationInterval completamenti
                    if (completionCount % observationInterval == 0) {
                        samplingCallback.accept(completionCount);
                    }
                    break;

                default:
                    break;
            }
        }
    }
}