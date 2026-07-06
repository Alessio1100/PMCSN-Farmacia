package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.SimulationValues;
import it.farmacia.model.Statistics;
import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

import java.text.DecimalFormat;
import java.util.*;

public class BraccioDue extends Center {
    public double firstArrive;
    public double lastArrive;
    public double lastDeparture;
    public InventorySystem inventorySystem;
    public Queue<Event> waitingQueue = new LinkedList<>();
    private boolean serverBusy = false;

    public List<Double> responseTimes = new ArrayList<>();
    public List<Double> waitingTimes = new ArrayList<>();
    public Map<Integer, Double> arrivalTimes = new HashMap<>();

    public Event currentServiceEvent;

    public int directQueue = 0;
    public int directService = 0;
    public int departingJobs = 0;
    public int extractedFromQueue = 0;
    public Map<Integer, Integer> departingFromQueue = new HashMap<>();

    // FIX 4: lastUpdateTime esposto per reset batch
    private double lastUpdateTime = 0;

    // Tempo BUSY integrato (∫ 1{server occupato} dt): base corretta per ρ ≤ 1 (a differenza di
    // service/elapsed, che accredita l'intero blocco compound a inizio servizio → ρ può sforare 1).
    public double busyTime = 0.0;

    public BraccioDue(Rvgs v, InventorySystem inventorySystem) {
        super(5, 1, v, "Braccio Due ");
        this.area = new Area[1];
        this.area[0] = new Area();
        this.inventorySystem = inventorySystem;
    }

    /**
     * FIX 4: Reset lastUpdateTime al tempo corrente per evitare delta enormi dopo reset batch.
     * Passare 0 causerebbe delta = t_current - 0 al primo evento successivo, accumulando
     * spuriamente t_current * numJobs job-secondi nell'area (RT/Nq gonfiati).
     */
    public void resetLastUpdateTime(double currentTime) {
        this.lastUpdateTime = currentTime;
    }

    @Override
    public int getNextCenter() {
        return -1;
    }

    @Override
    public double getService(int serverIndex) {
        v.rngs.selectStream(StreamType.STREAM_SERVICE_BRACCIODUE);
        // Braccio Due serve SOLO PRELIEVI cliente: l'ordine richiede K farmaci, a posizioni
        // diverse (anche classi diverse) → il tempo di servizio è la SOMMA di K prelievi
        // elementari indipendenti boundedNormal(15,7,10,30). Sostituisce il fork-join in K job.
        int k = (currentServiceEvent != null) ? currentServiceEvent.getNumeroFarmaciRichiesti()
                : (currentEvent != null ? currentEvent.getNumeroFarmaciRichiesti() : 1);
        if (k < 1) k = 1;
        double total = 0.0;
        for (int i = 0; i < k; i++) {
            total += v.boundedNormal(15, 5, 10, 20);
        }
        return total;
    }

    @Override
    public int processArrival() {
        lastArrive = currentEvent.getTime();
        numJobs++;
        arrivedJob++;

        if (firstArrive == 0) firstArrive = currentEvent.getTime();

        arrivalTimes.put(currentEvent.getId(), currentEvent.getTime());

        if (!serverBusy) {
            currentServiceEvent = cloneEvent(currentEvent);
            directService++;
            waitingTimes.add(0.0);
            startService();
            return 0;
        } else {
            directQueue++;
            departingFromQueue.put(currentEvent.getId(), waitingQueue.size());
            waitingQueue.add(cloneEvent(currentEvent));
            return -1;
        }
    }

    @Override
    public int processDeparture() {
        lastDeparture = currentEvent.getTime();
        completedJobs++;
        numJobs--;
        departingJobs++;

        int jobId = currentEvent.getId();

        Double arrivalTime = arrivalTimes.remove(jobId);
        if (arrivalTime != null) {
            responseTimes.add(currentEvent.getTime() - arrivalTime);
        }

        int queuePos = departingFromQueue.getOrDefault(jobId, 0);
        if (queuePos > 0) extractedFromQueue++;

        // NOTA: il consumo di inventario per-articolo (con le K classi dell'ordine) è gestito
        // in Simulation.handleBraccioDue (legge le classi dalla mappa orderClassi). Qui il centro
        // si occupa solo del tempo di servizio e della coda; l'ordine si completa comunque.
        serverBusy = false;

        if (!waitingQueue.isEmpty()) {
            currentEvent = waitingQueue.poll();
            currentServiceEvent = cloneEvent(currentEvent);

            Double nextArrival = arrivalTimes.get(currentEvent.getId());
            if (nextArrival != null) {
                double wait = lastDeparture - nextArrival;
                waitingTimes.add(wait);
            }

            startService();
            return 0;
        }

        return -1;
    }

    private void startService() {
        serverBusy = true;
        currentServiceEvent = cloneEvent(currentEvent);
        lastService = getService(0);
        servers.get(0).service += lastService;
        servers.get(0).served++;
        servers.get(0).idle = false;
        numBusyServers = 1;
    }

    private Event cloneEvent(Event original) {
        Event clone = new Event(original.getType(), original.getTime());
        clone.setCenter(original.getCenter());
        clone.setServer(original.getServer());
        clone.setClasseFarmaco(original.getClasseFarmaco());
        clone.setNumeroFarmaciRichiesti(original.getNumeroFarmaciRichiesti());
        clone.setOnline(original.checkOnline());
        clone.setExternal(original.isExternal());
        clone.setId(original.getId());
        clone.setMittente(original.getMittente());
        clone.setFirstArrivalTime(original.getFirstArrivalTime());
        return clone;
    }

    @Override
    public void updateStatistics(Event newEvent) {
        double delta = newEvent.getTime() - lastUpdateTime;
        if (delta >= 0) {
            if (numJobs > 0) {
                area[0].node += delta * numJobs;
                area[0].queue += delta * Math.max(0, numJobs - numBusyServers);
            }
            if (serverBusy) busyTime += delta;   // integrale tempo busy (ρ ≤ 1)
        }
        lastUpdateTime = newEvent.getTime();
        currentEvent = newEvent;
    }

    @Override
    public void printStatistics(Statistics globalStatistics) {
        DecimalFormat f = new DecimalFormat("###0.000");

        double avgResp = responseTimes.stream().mapToDouble(a->a).average().orElse(0.0);
        double avgWait = waitingTimes.stream().mapToDouble(a->a).average().orElse(0.0);
        System.out.println("\n  Numero di ORDINI serviti (prelievi clienti) ....... = " + responseTimes.size());
        System.out.println("  [REALE] Tempo di Risposta Medio del Nodo .......... = " + f.format(avgResp));
        System.out.println("  [REALE] Tempo di Attesa Medio in Coda  ............ = " + f.format(avgWait));
        System.out.println("  [AREA]  Tempo di Risposta Medio del Nodo .......... = " + f.format(getAvgWait(0, globalStatistics)));
        System.out.println("  [AREA]  Tempo di Attesa Medio in Coda  ............ = " + f.format(getAvgDelay(0, globalStatistics)));
        System.out.println("  Utilizzazione del Server .......................... = " + f.format(getUtilization(0)));
    }

    @Override public double getAvgInterarrival(int i, Statistics g) { return 0; }
    @Override public double getUtilization(int i) { return (lastUpdateTime > 0) ? busyTime / lastUpdateTime : 0; }
    @Override public double getAvgService(int i) { return (servers.get(0).served > 0) ? servers.get(0).service / servers.get(0).served : 0; }
    public Event getCurrentServiceEvent() { return currentServiceEvent; }
    public void resetCurrentServiceEvent() { currentServiceEvent = null; }

    @Override
    public double getAvgNode(int i, Statistics globalStatistics) { return (lastDeparture > firstArrive) ? area[0].node / (lastDeparture - firstArrive) : 0; }
    @Override
    public double getAvgQueue(int i, Statistics globalStatistics) { return (lastDeparture > firstArrive) ? area[0].queue / (lastDeparture - firstArrive) : 0; }

    // Denominatore = ORDINI completati al Braccio Due. NON si somma totalOutOfStock: l'OOS è
    // per-articolo dentro ordini che si completano comunque (un solo passaggio), mentre
    // braccioDueCompletions è per-ordine; sommarli gonfiava il denominatore e sottostimava W/Wq.
    @Override
    public double getAvgWait(int i, Statistics g) {
        double den = g.braccioDueCompletions;
        return (den > 0) ? area[0].node / den : 0;
    }
    @Override
    public double getAvgDelay(int i, Statistics g) {
        double den = g.braccioDueCompletions;
        return (den > 0) ? area[0].queue / den : 0;
    }

    public void clearBatchData() {
        responseTimes.clear();
        waitingTimes.clear();
        busyTime = 0.0;          // reset tempo busy per batch (utilizzazione per-batch)
        area[0].node = 0;        // area per-batch (era cumulativa tra batch)
        area[0].queue = 0;
    }

    public double getAvgNode2(int i) { return (lastDeparture > firstArrive) ? area[0].node / (lastDeparture - firstArrive) : 0; }
    public double getAvgQueue2(int i) { return (lastDeparture > firstArrive) ? area[0].queue / (lastDeparture - firstArrive) : 0; }
}