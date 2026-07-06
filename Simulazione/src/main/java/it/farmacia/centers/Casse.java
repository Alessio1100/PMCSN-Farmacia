package it.farmacia.centers;

import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

public class Casse extends MssqCenter {

    private final double[] meanServiceTimes;

    // Distribuzione del servizio (test di sensitività sulla "moda a zero" dell'esponenziale, §22):
    //   exp     = esponenziale (default, M/M/m, CV=1, moda in 0)
    //   erlang4 = Erlang-4 a PARITÀ di media (CV=0.5, moda>0, phase-type) → servizio più regolare.
    // Override: -Dcasse.dist=erlang4. Cambia SOLO la forma, non la media E[S].
    private static final String DIST = System.getProperty("casse.dist", "exp");

    public Casse(int numServer, Rvgs v, double[] meanServiceTimes) {
        super(1, numServer, v, "Casse Farmacia");
        this.meanServiceTimes = meanServiceTimes;
    }

    @Override
    public int getNextCenter() {
        return 3; // Dispatcher è centers[2], quindi nextCenter = 3
    }

    @Override
    public double getService(int serverIndex) {
        // ⚠️ CORREZIONE: Gestisce il caso in cui ci sono più server che meanServiceTimes
        int timeIndex;
        if (serverIndex < meanServiceTimes.length) {
            timeIndex = serverIndex;
        } else {
            // Se ci sono più server che tempi configurati, usa l'ultimo tempo disponibile
            timeIndex = meanServiceTimes.length - 1;
            System.out.println("WARN: Server " + serverIndex + " usa tempo di servizio dell'indice " + timeIndex);
        }

        v.rngs.selectStream(StreamType.STREAM_SERVICE_CASSE);
        double mu   = meanServiceTimes[timeIndex];
        double mean = 1.0 / mu;                       // E[S] = 1/mu (mu è un TASSO)
        if ("erlang4".equals(DIST)) {
            return v.erlang(4, mean / 4.0);           // media invariata, CV = 1/√4 = 0.5
        }
        return v.exponential(mean);                   // default: esponenziale, CV = 1
    }
}