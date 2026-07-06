package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

/**
 * Implementazione concreta di PriorityQueueCenter per il Braccio Uno.
 * Gestisce due code a priorità: alta priorità (da Dispatcher) e bassa priorità (da Magazziniere).
 * I job a bassa priorità vengono serviti solo se non ci sono job ad alta priorità in attesa.
 */
public class BraccioUno extends PriorityQueueCenter {

    public BraccioUno( Rvgs v) {
        super(4, v, "Braccio Uno");  // ID 4 come centro nel sistema
    }

    @Override
    public int getNextCenter() {
        return -1; // ID del centro successivo (es. consegna, uscita, ecc.)
    }

    @Override
    public double getService(int serverIndex) {
        v.rngs.selectStream(StreamType.STREAM_SERVICE_BRACCIOUNO); // Stream diverso per questo centro
        Event se = getCurrentServiceEvent();
        int k = (se != null) ? se.getNumeroFarmaciRichiesti() : 1;
        if (k < 1) k = 1;

        // STESSO robot, STESSO tempo per confezione del Braccio Due: boundedNormal(10,5,5,20).
        boolean carico = (se != null) && "magazziniere".equals(se.getMittente());
        if (carico) {
            // RIFORNIMENTO (carico): i Q farmaci sono della stessa classe alla stessa distanza →
            // una sola estrazione × Q (differisce dal prelievo solo nella varianza, non nella media).
            double d = v.boundedNormal(15, 5, 5, 20);
            return k * d;
        } else {
            // PRELIEVO cliente: i K farmaci sono a posizioni (e classi) diverse → somma di K
            // estrazioni indipendenti boundedNormal(10,5,5,20).
            double total = 0.0;
            for (int i = 0; i < k; i++) {
                total += v.boundedNormal(10, 5, 5, 20);
            }
            return total;
        }
    }

    @Override
    public double getArrival() {
        // Questo metodo potrebbe non essere utilizzato se gli arrivi sono gestiti
        // esternamente dalla Simulation (dal Dispatcher e Magazziniere)
        v.rngs.selectStream(105);
        double alfa = rvms.cdfExponential(2.0, 1);  // Media 2 minuti tra arrivi
        double u = v.uniform(alfa, 1);
        return rvms.idfExponential(0, u);
    }
}