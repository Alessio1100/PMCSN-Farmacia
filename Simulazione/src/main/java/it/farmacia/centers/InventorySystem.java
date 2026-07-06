package it.farmacia.centers;

import it.farmacia.model.InventoryConfig;
import it.farmacia.model.Order;

import java.util.*;
import java.text.DecimalFormat;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class InventorySystem {
    private List<Inventory> inventories;
    public Map<Integer, Order > pendingOrders;
    private double currentTime;
    private double totalSystemCost;
    private double totalShortageCost;
    private int totalOrdersToMagazziniere;
    private List<Order> completedOrders;
    private int totalCapacity;
    // Anti-leak: nelle run lunghe (infinito) la serie storica del livello per-articolo esplode
    // (recordLevel a ogni richiesta → milioni di entry). Disattivabile quando il trace non serve.
    public boolean recordHistory = true;

    public InventorySystem() {
        inventories = new ArrayList<>();
        pendingOrders = new HashMap<>();
        completedOrders = new ArrayList<>();
        currentTime = 0.0;
    }

    public void initializeInventories(List<InventoryConfig> configs, int totalCapacity) {
        int capacityPerClass = totalCapacity / configs.size();
        for (InventoryConfig config : configs) {
            Inventory inventory = new Inventory(
                    config.classId,
                    config.s,
                    config.S,
                    config.initialLevel,
                    capacityPerClass,
                    config.holdingCost,
                    config.shortageCost,
                    config.orderCost
            );
            inventories.add(inventory);
        }
    }

    public boolean requestItem(int classId) {
        Inventory inventory = getInventoryByClass(classId);
        if (inventory != null) {
            boolean fulfilled = inventory.fulfillDemand();
            if (recordHistory) inventory.recordLevel(currentTime);
            return fulfilled;
        }
        return false;
    }

    public List<Order> checkAndCreateOrders() {
        List<Order> newOrders = new ArrayList<>();

        for (Inventory inventory : inventories) {
            // Controlla se serve riordinare E se non c'è già un ordine in corso per questa classe
            if (inventory.needsReorder() && !pendingOrders.containsKey(inventory.getClassId())) {
                int orderQuantity = inventory.calculateOrderQuantity();

                if (orderQuantity > 0) {
                    Order order = new Order(inventory.getClassId(), orderQuantity, currentTime);
                    newOrders.add(order);

                    // 1. Registra l'ordine nei pending
                    pendingOrders.put(inventory.getClassId(), order);

                    // 2. Registra i COSTI (Order + Setup) e prepara il contatore arrivi
                    inventory.placeOrder(orderQuantity);

                    totalOrdersToMagazziniere++;
                }
            }
        }
        return newOrders;
    }

    public void receiveDelivery(int classId, int quantity, double deliveryTime, int id) {
        Inventory inventory = getInventoryByClass(classId);

        if (inventory != null && pendingOrders.containsKey(classId)) {
            // 1. Aggiorna fisicamente lo stock (senza duplicare i costi)
            inventory.receiveOrder(quantity);
            if (recordHistory) inventory.recordLevel(deliveryTime);

            // 2. Gestisci il tracciamento "pending" tramite la mappa nell'Inventory
            AtomicInteger remainingItems = inventory.currentOrderLevel.get(classId);

            if (remainingItems != null) {
                int left = remainingItems.addAndGet(-quantity); // Decrementa

                // Se abbiamo ricevuto tutto l'ordine
                if (left <= 0) {
                    Order completedOrder = pendingOrders.remove(classId);
                    if (completedOrder != null) {
                        completedOrder.setDelivered(deliveryTime);
                        completedOrders.add(completedOrder);
                        // inventory.currentOrderLevel.remove(classId); // Opzionale: pulizia
                    }
                }
            }
        }
    }

    public void updateTime(double newTime) {
        if (newTime <= currentTime) return;
        double timeInterval = newTime - currentTime;
        updateAllStatistics(timeInterval);
        currentTime = newTime;
    }

    private void updateAllStatistics(double timeInterval) {
        for (Inventory inventory : inventories) {
            inventory.updateStatistics(timeInterval);
        }
    }

    private void calculateTotalCost() {
        totalSystemCost = 0.0;
        totalShortageCost = 0.0;
        // Costi espressi come RATE per giornata operativa (43200 s), così sono confrontabili tra
        // run di orizzonte diverso (finito = 1 giornata; infinito/batch = molte giornate). Per
        // τ = 43200 s coincidono coi valori finiti precedenti (nessuna regressione su finito/run
        // singola). Prima il holding era diviso per 43200 hardcoded → corretto solo a 1 giornata.
        final double OPERATING_DAY = 43200.0;
        double days = (currentTime > 0.0) ? currentTime / OPERATING_DAY : 1.0;
        for (Inventory inventory : inventories) {
            // Holding: livello medio time-averaged L̄ = (∫ l dt)/τ; costo/giorno = L̄ · h_day.
            double avgLevel = (currentTime > 0.0) ? inventory.getTotalHolding() / currentTime : 0.0;
            double holdingCost = avgLevel * inventory.getHoldingCost();

            // Shortage (lost-sales): unità di domanda PERSA per giornata × penale per unità persa.
            double shortageCost = (inventory.getLostRequests() / days) * inventory.getShortageCost();

            // Ordine: costo fisso per ORDINE (setup) × numero di ordini per giornata.
            double orderingCost = (inventory.getTotalSetups() / days) * inventory.getOrderCost();

            totalSystemCost += holdingCost + shortageCost + orderingCost;
            totalShortageCost += shortageCost;
        }
    }

    public void writeLevelTimeSeries(String dir) {
        new java.io.File(dir).mkdirs();
        for (Inventory inv : inventories) {
            String path = dir + "/inventory_class" + inv.getClassId() + "_level.dat";
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(path))) {
                pw.printf(java.util.Locale.US, "# s=%d S=%d h=%.4f p=%.4f c=%.4f%n",
                        inv.getS_threshold(), inv.getS(),
                        inv.getHoldingCost(), inv.getShortageCost(), inv.getOrderCost());
                pw.println("# time_s\tlevel\tcum_oos\tholding_eur\tshortage_eur\torder_eur");
                pw.printf(java.util.Locale.US, "%.3f\t%d\t%d\t%.4f\t%.4f\t%.4f%n",
                        0.0, inv.getInitialLevel(), 0, 0.0, 0.0, 0.0);
                for (double[] e : inv.getLevelHistory())
                    pw.printf(java.util.Locale.US, "%.3f\t%d\t%d\t%.4f\t%.4f\t%.4f%n",
                            e[0], (int) e[1],
                            e.length > 2 ? (int) e[2] : 0,
                            e.length > 3 ? e[3] : 0.0,
                            e.length > 4 ? e[4] : 0.0,
                            e.length > 5 ? e[5] : 0.0);
            } catch (java.io.IOException e) {
                System.err.println("Errore scrittura " + path + ": " + e.getMessage());
            }
        }
    }

    public void printResults() {
        calculateTotalCost();
        DecimalFormat df = new DecimalFormat("###0.00");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("           RISULTATI INVENTORY SYSTEM");
        System.out.println("=".repeat(60));

        System.out.printf("Tempo simulazione: %.2f ore\n", currentTime/3600);
        System.out.printf("Ordini totali al magazziniere: %d\n", totalOrdersToMagazziniere);
        System.out.printf("Costo totale sistema: € %s\n\n", df.format(totalSystemCost));

        if (!completedOrders.isEmpty()) {
            java.util.DoubleSummaryStatistics lag = completedOrders.stream()
                    .mapToDouble(Order::getDeliveryLag).summaryStatistics();
            long oltre30 = completedOrders.stream()
                    .mapToDouble(Order::getDeliveryLag).filter(d -> d > 1800.0).count();
            long oltre60 = completedOrders.stream()
                    .mapToDouble(Order::getDeliveryLag).filter(d -> d > 3600.0).count();
            System.out.printf("Delivery lag: medio=%.1f min, min=%.1f, max=%.1f min%n",
                    lag.getAverage()/60.0, lag.getMin()/60.0, lag.getMax()/60.0);
            // VINCOLO: il carico di una fascia di revisione deve completarsi entro la fascia stessa,
            // altrimenti i carichi di fasce consecutive si sovrappongono al Braccio Uno.
            int n = completedOrders.size();
            System.out.printf("  Rifornimenti con lag > 30 min: %d/%d (%.1f%%)  | > 60 min: %d/%d (%.1f%%)%n%n",
                    oltre30, n, 100.0*oltre30/n, oltre60, n, 100.0*oltre60/n);
        }

        System.out.println("DETTAGLIO PER CLASSE:");
        System.out.println("-".repeat(92));
        System.out.printf("%-8s %-10s %-10s %-8s %-8s %-10s %-10s%n",
                "Classe", "Domanda", "Unita", "Setup", "Persi", "Liv.Medio", "Livello");
        System.out.printf("%-8s %-10s %-10s %-8s %-8s %-10s %-10s%n",
                "", "(unita)", "ordinate", "(ord.)", "(OOS)", "time-avg", "Finale");
        System.out.println("-".repeat(92));

        double sumDemand = 0.0, sumOrdered = 0.0;
        for (Inventory inventory : inventories) {
            double avgLevel = (currentTime > 0.0) ? inventory.getTotalHolding() / currentTime : 0.0;
            sumDemand  += inventory.getTotalDemand();
            sumOrdered += inventory.getTotalOrders();
            System.out.printf("%-8d %-10s %-10s %-8s %-8d %-10s %-10d%n",
                    inventory.getClassId(),
                    df.format(inventory.getTotalDemand()),
                    df.format(inventory.getTotalOrders()),
                    df.format(inventory.getTotalSetups()),
                    inventory.getLostRequests(),
                    df.format(avgLevel),
                    inventory.getCurrentLevel());
        }
        System.out.println("-".repeat(92));
        // CHECK DI FLUSSO (verifica canonica Leemis, SIS): a regime le UNITÀ ordinate ≈ UNITÀ
        // domandate. Lo scarto residuo = transitorio + unità ancora nella pipeline di rifornimento.
        double scarto = (sumDemand > 0.0) ? 100.0 * (sumOrdered - sumDemand) / sumDemand : 0.0;
        System.out.printf("CHECK FLUSSO  domanda=%s u  ordinate=%s u  scarto=%.1f%%  (atteso ~0%% a regime)%n",
                df.format(sumDemand), df.format(sumOrdered), scarto);
        System.out.println("=".repeat(60));
    }

    // Getters standard...
    public double getCurrentTime() { return currentTime; }
    public double getTotalSystemCost() { calculateTotalCost(); return totalSystemCost; }
    public double getTotalShortageCost(){ calculateTotalCost(); return totalShortageCost; }
    public int getTotalOrdersToMagazziniere() { return totalOrdersToMagazziniere; }

    public Inventory getInventoryByClass(int classId) {
        return inventories.stream().filter(inv -> inv.getClassId() == classId).findFirst().orElse(null);
    }

    /**
     * CDF cumulata per estrarre la classe di un farmaco richiesto con probabilità ∝ capacità S:
     * P(classe c) = S_c / Σ S. Così la domanda di ogni classe è proporzionale a quanto può
     * immagazzinare/rifornire → carico bilanciato tra le classi e meno OOS da squilibrio.
     * cdf[c-1] = probabilità cumulata fino alla classe c (classId crescente 1..n).
     */
    public double[] getCapacityCdf() {
        int n = inventories.size();
        double total = 0.0;
        for (int c = 1; c <= n; c++) {
            Inventory inv = getInventoryByClass(c);
            if (inv != null) total += inv.getS();
        }
        double[] cdf = new double[n];
        double cum = 0.0;
        for (int c = 1; c <= n; c++) {
            Inventory inv = getInventoryByClass(c);
            cum += (inv != null ? inv.getS() : 0.0);
            cdf[c - 1] = (total > 0) ? cum / total : (double) c / n;
        }
        return cdf;
    }

    public List<Inventory> getAllInventories() { return new ArrayList<>(inventories); }
    public boolean hasPendingOrder(int classId) { return pendingOrders.containsKey(classId); }
    public double getAverageDeliveryLag() {
        if (completedOrders.isEmpty()) return 0.0;
        return completedOrders.stream().mapToDouble(Order::getDeliveryLag).average().orElse(0.0);
    }

    /** Massimo delivery lag tra TUTTI gli ordini consegnati (s). Per il vincolo "lag < R". */
    public double getMaxDeliveryLag() {
        if (completedOrders.isEmpty()) return 0.0;
        return completedOrders.stream().mapToDouble(Order::getDeliveryLag).max().orElse(0.0);
    }

    /** Frazione di ordini con delivery lag > threshold (s). =0 ⇒ nessun ordine sfora la finestra. */
    public double getFractionLagOver(double threshold) {
        if (completedOrders.isEmpty()) return 0.0;
        long over = completedOrders.stream().mapToDouble(Order::getDeliveryLag).filter(d -> d > threshold).count();
        return (double) over / completedOrders.size();
    }

    /** Anti-leak: svuota la lista degli ordini completati (cresce con ogni consegna nelle run lunghe). */
    public void clearCompletedOrders() { completedOrders.clear(); }
}