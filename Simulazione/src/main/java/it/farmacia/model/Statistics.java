package it.farmacia.model;

public class Statistics {

    public double[] avgInterarrivals;
    public double[] avgWait;
    public double[] avgDelay;
    public double[] avgNode;
    public double[] avgQueue;
    public double[] utilization;
    public double[] avgService;
    // Split statistiche del Braccio Uno per coda: HIGH = carico/rifornimento, LOW = prelievi cliente.
    // (0 per i centri senza priorità). Servono per i .dat per-coda del finito.
    public double utilHigh = 0.0;   // utilization[0] totale = utilHigh + utilLow
    public double utilLow  = 0.0;
    public double avgWaitHigh = 0.0,  avgWaitLow = 0.0;    // E[T] per coda
    public double avgDelayHigh = 0.0, avgDelayLow = 0.0;   // E[Tq] per coda
    public double avgNodeHigh = 0.0,  avgNodeLow = 0.0;    // E[N] per coda
    public double avgQueueHigh = 0.0, avgQueueLow = 0.0;   // E[Nq] per coda
    public double lossProbability;
    public double totalInterrarival;
    public double abandonmentRate = 0.0;  // % clienti che abbandonano la coda (per batch)

    // 🚩 Aggiunta per Farmacia
    public int totalSuccessfulExits;   // completamenti corretti
    public int totalReneging;          // abbandoni in coda
    public int totalOutOfStock;        // out of stock inventory
    public int totalGeneratedJob;
    public int totalGeneratedFarmaci;
    public int totalSuccessfulExitsBracci;
    public int cassaCompletions = 0;
    public int cassaOnlineCompletions = 0;
    public int dispatcherCompletions = 0;
    public int magazziniereCompletions = 0;
    public int braccioUnoCompletions = 0;
    public int braccioDueCompletions = 0;
    public int lowPriorityCompletions = 0;
    public int highPriorityCompletions = 0;
    public int outOfStockBraccioUno = 0;
    public int outOfStockBraccioDue = 0;
    public int jobSpacchettati =0;
    // Conteggi PER-ARTICOLO (non per-ordine): farmaci effettivamente venduti (prelievi soddisfatti)
    // e caricati a banco (rifornimenti consegnati). Mantengono il significato "a farmaco" che nel
    // vecchio modello fork era dato dai completamenti per-frammento.
    public int farmaciVenduti = 0;
    public int farmaciCaricati = 0;
    // Cumulativi di REPLICA/RUN: NON azzerati per-finestra/batch (servono ai riepiloghi aggregati
    // di orizzonte finito/infinito: abbandoni medi, OOS medi, ecc.).
    public int totalRenegingCum = 0;
    public int totalOosCum = 0;
    public int totalExitsCum = 0;
    public int farmaciVendutiCum = 0;
    public int farmaciCaricatiCum = 0;
    public int generatiCum = 0;

    public Statistics(int numServer) {
        avgInterarrivals = new double[numServer];
        avgWait = new double[numServer];
        avgDelay = new double[numServer];
        avgNode = new double[numServer];
        avgQueue = new double[numServer];
        utilization = new double[numServer];
        avgService = new double[numServer];
        totalSuccessfulExits = 0;
        totalReneging = 0;
        totalOutOfStock = 0;
    }

    // 🚩 Aggiunta dei metodi di aggiornamento usati nella simulazione:
    public void registerSuccessfulExit(double time) {
        totalSuccessfulExits++;
        totalExitsCum++;
    }

    public void registerAbandon(String classeFarmaco, double time) {
        totalReneging++;
        totalRenegingCum++;
    }

    public void resetAbandon(){totalReneging=0;}

    public void registerOutOfStock(String classeFarmaco, double time) {
        totalOutOfStock++;
        totalOosCum++;
    }

    public void resetOutOfStock(){totalOutOfStock=0;}

    public void registerGeneratedJob() {
        totalGeneratedJob++;
    }

    public void registerFarmaciRichiesti(int numFarmaci) {
        totalGeneratedFarmaci += numFarmaci;
        generatiCum += numFarmaci;
    }
    public void registerSpacchettamento(){jobSpacchettati++;}
    public void registerSuccessfulExitBracci(){
        totalSuccessfulExitsBracci++;
    }

    public void registerCassaCompletion() { cassaCompletions++; }

    public void registerCassaOnlineCompletion() { cassaOnlineCompletions++; }

    public void registerDispatcherCompletion() { dispatcherCompletions++; }

    public void registerMagazziniereCompletion() { magazziniereCompletions++; }

    public void registerBraccioUnoCompletion() { braccioUnoCompletions++; }

    public void registerBraccioDueCompletion() { braccioDueCompletions++; }

    public void registerLowPriorityCompletion() { lowPriorityCompletions++;}

    public void registerHighPriorityCompletion() {highPriorityCompletions++;}

    public void registerOutOfStockBraccioDue(String classeFarmaco, double current) {
        outOfStockBraccioDue++;
    }

    public void registerOutOfStockBraccioUno(String classeFarmaco, double current) {
        outOfStockBraccioUno++;
    }

    public void registerFarmaciVenduti(int n) { farmaciVenduti += n; farmaciVendutiCum += n; }
    public void registerFarmaciCaricati(int n) { farmaciCaricati += n; farmaciCaricatiCum += n; }
}
