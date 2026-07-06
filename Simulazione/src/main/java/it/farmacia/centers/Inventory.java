package it.farmacia.centers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Inventory {
    protected int classId;
    private int currentLevel;
    protected int s; // soglia di riordino
    protected int S; // livello massimo
    protected int initialLevel;
    protected int capacity; // capacità allocata staticamente
    public Map<Integer,AtomicInteger> currentOrderLevel = new HashMap<>();

    // Costi
    protected double holdingCost;   // h_i
    protected double shortageCost;  // p_i
    protected double orderCost;     // c_i

    // Statistiche
    protected double totalDemand;
    protected double totalOrders;
    protected double totalSetups;
    protected double totalHolding;
    protected double totalShortage;
    protected int lostRequests;

    // Serie storica del livello: ogni entry = {time, level}
    private final List<double[]> levelHistory = new ArrayList<>();

    public Inventory(int classId, int s, int S, int initialLevel, int capacity,
                     double holdingCost, double shortageCost, double orderCost) {
        this.classId = classId;
        this.s = s;
        this.S = S;
        this.initialLevel = initialLevel;
        this.currentLevel = initialLevel;
        this.capacity = capacity;
        this.holdingCost = holdingCost;
        this.shortageCost = shortageCost;
        this.orderCost = orderCost;

        resetStatistics();
    }

    public void resetStatistics() {
        totalDemand = 0.0;
        totalOrders = 0.0;
        totalSetups = 0.0;
        totalHolding = 0.0;
        totalShortage = 0.0;
        lostRequests = 0;
    }

    public boolean needsReorder() {
        return currentLevel <= s;
    }

    public int calculateOrderQuantity() {
        if (needsReorder()) {
            return S - currentLevel;
        }
        return 0;
    }

    /**
     * NUOVO METODO: Chiamato SOLO quando l'ordine viene creato (1 volta per ordine)
     * Qui registriamo i costi fissi.
     */
    public void placeOrder(int quantity) {
        // Semantica canonica Leemis (SIS): "order" = UNITÀ ordinate (Σ quantità),
        // "setup" = NUMERO di ordini (frequenza di riordino). Tenuti separati perché:
        //  - costo d'ordine = setup × orderCost (costo fisso amministrativo per ordine);
        //  - check di flusso = unità ordinate ≈ unità domandate (a regime, verifica Leemis).
        totalOrders += quantity;  // unità ordinate (Σ quantità)
        totalSetups++;            // numero di ordini (1 setup per ordine logico)

        // Inizializza il tracciamento dei pezzi in arrivo
        currentOrderLevel.put(classId, new AtomicInteger(quantity));
    }

    /**
     * MODIFICATO: Chiamato per ogni farmaco che arriva fisicamente.
     * Incrementa solo il livello, NON i costi.
     */
    public void receiveOrder(int orderAmount) {
        currentLevel += orderAmount;

        // Decremento del counter avviene esternamente o qui se passiamo l'AtomicInteger,
        // ma la logica attuale dell'InventorySystem gestisce il decremento map esternamente.
        // Per sicurezza, se la logica AtomicInteger è gestita in InventorySystem, qui aggiorniamo solo il livello fisico.
    }

    public boolean fulfillDemand() {
        totalDemand += 1;
        if (currentLevel > 0) {
            currentLevel--;
            return true;
        } else {
            lostRequests++;
            return false;
        }
    }

    // Integrale time-averaged dell'inventario (Leemis): holding = ∫ max(l,0) dt.
    // MODELLO LOST-SALES: la domanda non soddisfatta è PERSA (out-of-stock), NON messa in
    // backorder → currentLevel resta sempre ≥ 0 → l'integrale di shortage (∫ max(-l,0) dt) è
    // 0 per costruzione (totalShortage resta 0). La metrica di servizio quindi NON è il
    // livello-shortage time-averaged del libro, ma il CONTEGGIO della domanda persa
    // (lostRequests → P(OOS)). Scelta giustificata: in farmacia il cliente non aspetta il
    // backorder, va altrove. Vedi calculateTotalCost (shortage = lost-sales).
    public void updateStatistics(double timeInterval) {
        totalHolding += currentLevel * timeInterval;   // currentLevel ≥ 0 (lost-sales)
    }

    // Giornata operativa di riferimento per normalizzare il holding (coerente con
    // InventorySystem.calculateTotalCost): holding_cost_giornaliero = h · (∫level dt)/τ.
    private static final double OPERATING_DAY = 43200.0;

    // Registra lo stato corrente nella serie storica:
    //   (time, level, cum_oos, holding_eur, shortage_eur, order_eur)
    // Le tre voci di costo sono CUMULATE e coerenti col modello di costo del codice:
    //   holding_eur  = h · (∫level dt)/τ   (totalHolding è già integrato fino a `time` da updateTime)
    //   shortage_eur = p · lostRequests    (lost-sales: costo per unità persa)
    //   order_eur    = c · totalSetups     (costo amministrativo per ordine emesso)
    // Così il plot legge le statistiche dai .dat invece di ri-derivarle dal solo livello.
    public void recordLevel(double time) {
        double holdingEur  = holdingCost  * totalHolding / OPERATING_DAY;
        double shortageEur = shortageCost * lostRequests;
        double orderEur    = orderCost    * totalSetups;
        levelHistory.add(new double[]{time, currentLevel, lostRequests,
                holdingEur, shortageEur, orderEur});
    }

    public List<double[]> getLevelHistory() { return levelHistory; }
    public void clearLevelHistory()         { levelHistory.clear(); }
    public int getS_threshold()             { return s; }
    public int getInitialLevel()            { return initialLevel; }

    // Getters
    public int getClassId() { return classId; }
    public int getCurrentLevel() { return currentLevel; }
    public int getS() { return S; }
    public int getCapacity() { return capacity; }
    public double getTotalDemand() { return totalDemand; }
    public double getTotalOrders() { return totalOrders; }
    public double getTotalSetups() { return totalSetups; }
    public int getLostRequests() { return lostRequests; }
    public double getHoldingCost() { return holdingCost; }
    public double getShortageCost() { return shortageCost; }
    public double getOrderCost() { return orderCost; }
    public double getTotalHolding() { return totalHolding; }
    public double getTotalShortage() { return totalShortage; }
}