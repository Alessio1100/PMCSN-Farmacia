package it.farmacia.control;

public class InfiniteHorizonSimulation {

    public int numBatches;
    public int batchSize;
    public double[] arrivalRates = {0.045, 0.303, 0.076, 0.606, 0.05, 0.273, 0.076};
    public int[] inventory_config = {56, 49, 42, 39, 25}; //{80, 70, 60, 55, 35}

    // Batch means (slide lect26): k=64, gap=0. La batch size di default = 1024 è il valore CALIBRATO
    // da BatchCalibration (Chatfield |r1| < 2/sqrt(64)=0.25 su tutti i centri-cliente; max=0.186 su
    // Online, il centro a traffico più basso). Il sistema mescola velocemente → bastano 1024 job/batch.
    // Override con -Dnb -Dbs -Dgap. (Per un cuscinetto extra su Online: -Dbs=2048.)
    public InfiniteHorizonSimulation(int numBatches, int batchSize) {
        this.numBatches = numBatches;
        this.batchSize = batchSize;
    }

    public static void main(String[] args) {
        int num_batchs = Integer.getInteger("nb", 64);
        int batch_size = Integer.getInteger("bs", 1024);
        InfiniteHorizonSimulation infiniteHorizonSimulation = new InfiniteHorizonSimulation(num_batchs, batch_size);
        infiniteHorizonSimulation.run();
    }

    // viene eseguita un unica lunga run, per ogni batch di size batchSize vengono raccolte le statistiche di ogni centro
    // viene poi calcolato l'intervallo di confidenza per ogni statistica utilizzando come campione i valori estratti da ciascun batch
    // quindi la dimensione del campione per la stima di ogni statistica ha dimensione numBatches
    public void run() {
        // Per ogni fascia oraria (cambia solo il lambda) devo runnare la simulazione a orizzonte infinito
        // avendo scelto una certa configurazione per ogni centro
        Simulation simulation = new Simulation();
        // Setto il lambda per la fascia oraria corrente
        //
        // Media Gruppo Basso Afflusso: ≈ 0.0095
        // Media Gruppo Afflusso Medio: ≈ 0.0154
        // Media Gruppo Alto Afflusso : ≈ 0.0222
        //
        // λ di default 0.01595 (coerente col transitorio); override con -Dlambda per provare altri tassi
        // (es. 0.0190975 = picco "+20%" della relazione).
        double lambda = Double.parseDouble(System.getProperty("lambda", "0.0190975"));
        simulation.setArrivalRateInf(lambda);
        int gap = Integer.getInteger("gap", 0); // gap inter-batch per ridurre l'autocorrelazione
        int nCasse = Integer.getInteger("casse", 5);  // n. casse fisiche (override -Dcasse)
        int nMag   = Integer.getInteger("mag", 2);    // n. magazzinieri (override -Dmag) — pipeline riordino
        simulation.runInfiniteHorizonSimulation(numBatches, batchSize, inventory_config,
                new int[]{nCasse, nMag}, true, gap);
    }
}
