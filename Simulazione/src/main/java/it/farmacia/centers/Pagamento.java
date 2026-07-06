package it.farmacia.centers;

import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

/**
 * Centro CassePagamento: il cliente FISICO, dopo che il robot ha prelevato i suoi farmaci al
 * braccio, paga ed esce dal sistema. È un multiserver a CODA SINGOLA con servizio omogeneo
 * (default 2 serventi), esponenziale di media 60 s. NON è bloccante: il servente viene liberato
 * al PROPRIO departure (gestito in Simulation.handlePagamento), come per le casse fisiche.
 *
 * I clienti ONLINE (e-commerce, già pagati) NON passano da qui: escono direttamente dal braccio.
 *
 * Estende MssqCenter per riutilizzare coda, selezione server, aree e utilizzazione per-servente.
 */
public class Pagamento extends MssqCenter {

    public static final double MEAN_SERVICE = 60.0; // E[S] pagamento (s)

    public Pagamento(int numServer, Rvgs v) {
        super(7, numServer, v, "Casse Pagamento");
    }

    @Override
    public int getNextCenter() {
        return 0; // uscita dal sistema
    }

    @Override
    public double getService(int serverIndex) {
        v.rngs.selectStream(StreamType.STREAM_SERVICE_PAGAMENTO);
        return v.exponential(MEAN_SERVICE);
    }
}
