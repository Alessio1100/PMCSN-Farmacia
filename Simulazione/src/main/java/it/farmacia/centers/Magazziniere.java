package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

import java.text.DecimalFormat;

//rappresenta il centro della banchina della metro (singola coda, singolo server)
public class Magazziniere extends MssqCenter {

    public double meanServiceTime = 2.0;

    public Magazziniere( int numServer, Rvgs v) {
        super(1, numServer, v, "Magazziniere");
        //this.area = new Area[1];
        //this.area[0] = new Area();
    }

    //la banchina della metro è il centro finale del sistema, non c'è un centro successivo
    @Override
    public int getNextCenter() {
        return 4;
    }

    // Tempo medio di handling per confezione del magazziniere: prelievo scatola dal magazzino +
    // spacchettamento + immissione in coda. Deve essere > E[S] del braccio per confezione (15s),
    // così il magazziniere (produttore) è più lento del robot (consumatore) e la coda di carico al
    // Braccio Uno NON satura (argomento produttore-consumatore).
    public static final double HANDLING_PER_CONFEZIONE = 18.0; // s

    @Override
    public double getService(int serverIndex) {
        // Servizio PER ORDINE di rifornimento, proporzionale al numero Q di confezioni del carico.
        int q = (currentEvent != null) ? currentEvent.getNumeroFarmaciRichiesti() : 1;
        return serviceForQuantity(q);
    }

    /**
     * Tempo di servizio del magazziniere per un carico di q confezioni: prepara q confezioni
     * (prelievo + spacchettamento + immissione), una alla volta. Esposto per il drenaggio della
     * coda in Simulation.handleMagazziniere, dove currentEvent non è ancora il prossimo ordine.
     */
    public double serviceForQuantity(int q) {
        v.rngs.selectStream(StreamType.STREAM_SERVICE_MAGAZZINIERE);
        if (q < 1) q = 1;
        double total = 0.0;
        for (int i = 0; i < q; i++) {
            total += v.exponential(HANDLING_PER_CONFEZIONE);
        }
        return total;
    }
}



