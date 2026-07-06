package it.farmacia.control;

import it.farmacia.centers.Inventory;
import it.farmacia.centers.InventorySystem;
import it.farmacia.model.InventoryConfig;
import it.farmacia.model.Order;
import it.farmacia.utils.Rngs;
import it.farmacia.utils.Rvgs;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST DEGENERATO DI VERIFICA DELL'INVENTORY (Algoritmo 1.1: "il modello computazionale è
 * coerente con quello di specifica?").
 *
 * Idea: il nostro InventorySystem, nel sistema completo, è accoppiato (domanda compound dal flusso
 * clienti, delivery lag endogeno, multi-classe, chunking, priorità) e NON ha forma chiusa con cui
 * confrontarsi. Lo VERIFICHIAMO collassandolo al regime del Simple Inventory System di Leemis-Park
 * (programma Sis3, utils/): UNA classe, domanda di Poisson INDIPENDENTE di tasso noto, revisione
 * periodica, delivery lag esogeno Uniform(0,1).
 *
 * Strategia: pilotiamo DUE modelli sulla STESSA identica realizzazione (stesso Rngs/seed, stream 0
 * = domanda, stream 1 = lag, nello stesso ordine di estrazione):
 *   (A) RIFERIMENTO  = adattamento fedele del loop di Sis3.java (backlogging),
 *   (B) NOSTRO       = InventorySystem/Inventory tramite la sua API pubblica.
 * Parametri scelti per NON avere stockout → in quel regime lost-sales (nostro) ≡ backlogging
 * (Sis3): le due implementazioni devono produrre statistiche IDENTICHE (stessa traiettoria, stesse
 * estrazioni). Qualunque divergenza segnala un BUG nella NOSTRA contabilità (s,S), non nel modello.
 *
 * Statistiche confrontate (canoniche Leemis): domanda totale (unità), ordine totale (unità),
 * setup (n. ordini), holding integrale ∫l·dt, livello finale. Più i check: livello minimo > 0
 * (nessun backorder) e lostRequests = 0 (nessuno stockout) → regime di agreement valido; e il
 * check di flusso unità ordinate ≈ unità domandate.
 *
 * Override opzionali: -Dinv.s -Dinv.S -Dinv.rate -Dinv.stop -Dinv.seed
 */
public class InventoryDegenerateTest {

    static final int    S_THRESH = Integer.getInteger("inv.s", 40);     // soglia s
    static final int    S_MAX    = Integer.getInteger("inv.S", 120);    // livello S
    static final double RATE     = Double.parseDouble(System.getProperty("inv.rate", "5.0")); // domande/intervallo
    static final double R_REVIEW = 1.0;                                 // periodo di revisione (come Sis3)
    static final double STOP     = Double.parseDouble(System.getProperty("inv.stop", "20000")); // intervalli
    static final long   SEED     = Long.getLong("inv.seed", 123456789L);
    static final double INFINITY = 1.0e15;

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  TEST DEGENERATO INVENTORY - verifica vs Simple Inventory System (Leemis/Sis3)");
        System.out.println("=".repeat(78));
        System.out.printf("Regime: 1 classe, domanda Poisson rate=%.1f/intervallo, (s,S)=(%d,%d), "
                + "revisione R=%.1f, lag~U(0,1), STOP=%.0f, seed=%d%n%n",
                RATE, S_THRESH, S_MAX, R_REVIEW, STOP, SEED);

        double[] ref = runReference();
        double[] our = runOurInventory();

        String[] etich = {"Domanda (unita)", "Ordine (unita)", "Setup (n. ordini)",
                          "Holding integrale", "Livello finale", "Livello minimo", "Persi (OOS)"};
        System.out.printf("%-22s %18s %18s %12s%n", "Statistica", "RIFERIMENTO Sis3", "NOSTRO InvSys", "scarto rel.");
        System.out.println("-".repeat(78));
        boolean allMatch = true;
        for (int i = 0; i < etich.length; i++) {
            double rel = (Math.abs(ref[i]) > 1e-12) ? Math.abs(our[i] - ref[i]) / Math.abs(ref[i])
                                                     : Math.abs(our[i] - ref[i]);
            if (rel > 1e-9) allMatch = false;
            System.out.printf("%-22s %18.4f %18.4f %12.2e%n", etich[i], ref[i], our[i], rel);
        }
        System.out.println("-".repeat(78));

        // Check di regime: nessuno stockout (altrimenti backlogging != lost-sales → confronto non valido)
        boolean noStockoutRef = ref[5] > 0.0;   // livello minimo riferimento > 0
        boolean noStockoutOur = our[6] == 0.0;   // persi nostro == 0
        // Check di flusso (Leemis): unità ordinate ≈ unità domandate (a regime)
        double scartoFlusso = 100.0 * (our[1] - our[0]) / our[0];

        System.out.printf("%nAvg domanda/intervallo = %.4f   (atteso ~ rate = %.1f)%n", our[0] / STOP, RATE);
        System.out.printf("Avg ordine /intervallo = %.4f   (check di flusso: deve ~ uguagliare la domanda)%n", our[1] / STOP);
        System.out.printf("Check di flusso (ordinate vs domandate) = %+.2f%%%n", scartoFlusso);
        System.out.printf("Regime senza stockout: riferimento minLevel>0 = %b | nostro persi=0 = %b%n",
                noStockoutRef, noStockoutOur);

        System.out.println("\n" + "=".repeat(78));
        if (!noStockoutRef || !noStockoutOur) {
            System.out.println("  [WARN] REGIME NON VALIDO: si sono verificati stockout -> alza s (-Dinv.s) e ripeti.");
        } else if (allMatch) {
            System.out.println("  [PASS] il nostro InventorySystem RIPRODUCE ESATTAMENTE il SIS di Leemis.");
            System.out.println("         Meccanica (s,S), integrale di holding, conteggi ordine/setup VERIFICATI.");
        } else {
            System.out.println("  [FAIL] divergenza dal riferimento Sis3 -> BUG nella contabilita' inventory.");
        }
        System.out.println("=".repeat(78));
    }

    /**
     * RIFERIMENTO: adattamento fedele del loop next-event di Sis3.java (Leemis-Park), backlogging.
     * Unica differenza convenzionale: trigger di riordino "level <= s" (come il nostro
     * InventorySystem) invece dello stretto "<" di Sis3 (convenzione di bordo immateriale); e un
     * solo ordine pendente (come il nostro). Eventi: demand (stream 0), review (R), arrive (lag
     * stream 1). Stessa priorità di Sis3 in caso di pari tempo (demand, review, arrive).
     */
    static double[] runReference() {
        Rngs rngs = new Rngs();
        rngs.plantSeeds(SEED);
        Rvgs rvgs = new Rvgs(rngs);

        long level = S_MAX;
        long order = 0;
        boolean pending = false;

        double current = 0.0;
        rngs.selectStream(0);
        double tDemand = current + rvgs.exponential(1.0 / RATE);
        double tReview = current + R_REVIEW;
        double tArrive = INFINITY;

        double sumDemand = 0, sumOrder = 0, sumSetup = 0, holding = 0;
        long minLevel = level, lost = 0;

        while (current < STOP) {
            double next = Math.min(tDemand, Math.min(tReview, tArrive));
            if (level > 0) holding += (next - current) * level;   // backlogging: shortage se level<0 (qui non accade)
            current = next;

            if (current == tDemand) {                 // domanda (1 unità)
                sumDemand += 1;
                level--;                              // backlogging: può andare negativo
                rngs.selectStream(0);
                tDemand = current + rvgs.exponential(1.0 / RATE);
            } else if (current == tReview) {          // revisione (s,S)
                if (level <= S_THRESH && !pending) {
                    order = S_MAX - level;
                    sumOrder += order;
                    sumSetup += 1;
                    pending = true;
                    rngs.selectStream(1);
                    tArrive = current + rvgs.uniform(0.0, 1.0);   // delivery lag esogeno
                }
                tReview = current + R_REVIEW;
            } else {                                  // arrivo ordine
                level += order;
                order = 0;
                pending = false;
                tArrive = INFINITY;
            }
            if (level < minLevel) minLevel = level;
            if (level < 0) lost++;
        }
        return new double[]{sumDemand, sumOrder, sumSetup, holding, level, minLevel, lost};
    }

    /**
     * NOSTRO: identica struttura di loop, ma la logica (s,S), l'integrale di holding e i conteggi
     * passano per l'API pubblica di InventorySystem/Inventory. Stesse estrazioni (stream 0 domanda,
     * stream 1 lag) nello stesso ordine → stessa realizzazione del RIFERIMENTO.
     */
    static double[] runOurInventory() {
        Rngs rngs = new Rngs();
        rngs.plantSeeds(SEED);
        Rvgs rvgs = new Rvgs(rngs);

        InventorySystem inv = new InventorySystem();
        List<InventoryConfig> cfgs = new ArrayList<>();
        // costi irrilevanti per la verifica MECCANICA (qui contano i conteggi, non i costi)
        cfgs.add(new InventoryConfig(1, S_THRESH, S_MAX, S_MAX, 0.0, 0.0, 0.0));
        inv.initializeInventories(cfgs, S_MAX);

        double current = 0.0;
        rngs.selectStream(0);
        double tDemand = current + rvgs.exponential(1.0 / RATE);
        double tReview = current + R_REVIEW;
        double tArrive = INFINITY;
        int pendingQty = 0;
        int orderId = 0;
        long minLevel = inv.getInventoryByClass(1).getCurrentLevel();

        while (current < STOP) {
            double next = Math.min(tDemand, Math.min(tReview, tArrive));
            inv.updateTime(next);     // accumula holding su [current, next] al livello corrente
            current = next;

            if (current == tDemand) {                 // domanda
                inv.requestItem(1);
                rngs.selectStream(0);
                tDemand = current + rvgs.exponential(1.0 / RATE);
            } else if (current == tReview) {          // revisione (s,S)
                List<Order> orders = inv.checkAndCreateOrders();
                if (!orders.isEmpty()) {
                    pendingQty = orders.get(0).getQuantity();
                    rngs.selectStream(1);
                    tArrive = current + rvgs.uniform(0.0, 1.0);
                }
                tReview = current + R_REVIEW;
            } else {                                  // arrivo ordine
                inv.receiveDelivery(1, pendingQty, current, ++orderId);
                pendingQty = 0;
                tArrive = INFINITY;
            }
            long lv = inv.getInventoryByClass(1).getCurrentLevel();
            if (lv < minLevel) minLevel = lv;
        }
        Inventory i = inv.getInventoryByClass(1);
        return new double[]{ i.getTotalDemand(), i.getTotalOrders(), i.getTotalSetups(),
                             i.getTotalHolding(), i.getCurrentLevel(), minLevel, i.getLostRequests() };
    }
}
