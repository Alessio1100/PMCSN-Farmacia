package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.Statistics;
import it.farmacia.utils.Rvgs;

import java.text.DecimalFormat;
import java.util.*;

import static it.farmacia.model.StreamType.STREAM_SERVICE_ONLINE;

public class CassaOnline extends Center {

    double firstArrive;
    double lastArrive;
    public double lastDeparture;
    public Queue<Event> waitingQueue = new LinkedList<>();
    public boolean serverBusy = false;
    public int contaJobCoda = 0;
    public Map<Integer, Double> arrivalTimes = new HashMap<>();
    private Map<Integer, Double> serviceStartTimes = new HashMap<>();

    private List<Double> responseTimes = new ArrayList<>();
    public List<Double> waitingTimes = new ArrayList<>();
    public List<Double> serviceTimes = new ArrayList<>();

    double avgW;
    double avgWq;
    double avgS;

    public Event currentServiceEvent;

    // FIX 4: lastUpdateTime esposto per reset batch
    private double lastUpdateTime = 0.0;
    // Tempo di inizio del batch corrente (per calcolo avgNode/avgQueue per-batch)
    private double batchStartTime = 0.0;

    public CassaOnline(Rvgs v) {
        super(2, 1, v, "Cassa Online");
        this.area = new Area[1];
        this.area[0] = new Area();
    }

    /**
     * FIX 4: Reset lastUpdateTime al tempo corrente per evitare delta enormi dopo reset batch.
     * Resetta anche l'area e il batchStartTime per il calcolo corretto di avgNode/avgQueue.
     */
    public void resetLastUpdateTime(double currentTime) {
        this.lastUpdateTime = currentTime;
        this.batchStartTime = currentTime;
        this.area[0].node    = 0;
        this.area[0].queue   = 0;
        this.area[0].service = 0;
    }

    @Override
    public int getNextCenter() {
        return 3; // Dispatcher
    }

    @Override
    public double getService(int serverIndex) {
        v.rngs.selectStream(STREAM_SERVICE_ONLINE);
        return v.exponential(60);
    }

    @Override
    public int processArrival() {
        lastArrive = currentEvent.getTime();
        arrivedJob++;
        numJobs++;

        if (firstArrive == 0.0) {
            firstArrive = currentEvent.getTime();
        }

        arrivalTimes.put(currentEvent.getId(), currentEvent.getTime());

        if (!serverBusy) {
            currentServiceEvent = cloneEvent(currentEvent);
            serviceStartTimes.put(currentEvent.getId(), currentEvent.getTime());
            waitingTimes.add(0.0);
            startService();
            return 0;
        } else {
            waitingQueue.add(cloneEvent(currentEvent));
            contaJobCoda++;
            return -1;
        }
    }

    @Override
    public int processDeparture() {
        lastDeparture = currentEvent.getTime();
        completedJobs++;

        // FIX 2: numJobs-- decommentato.
        // Il job passa al Dispatcher: per le statistiche di QUESTO nodo
        // il lavoro è finito qui. Senza questo decremento numJobs cresce
        // indefinitamente → area[0].node diverge → response time ×100.
        numJobs--;

        this.serverBusy = false;
        int jobId = currentEvent.getId();

        // Calcolo Response Time (Reale)
        Double arrivalTime = arrivalTimes.get(jobId);
        if (arrivalTime != null) {
            double responseTime = currentEvent.getTime() - arrivalTime;
            responseTimes.add(responseTime);
            arrivalTimes.remove(jobId);
        }

        // Calcolo Service Time (Reale)
        Double startTime = serviceStartTimes.get(jobId);
        if (startTime != null) {
            serviceTimes.add(currentEvent.getTime() - startTime);
            serviceStartTimes.remove(jobId);
        }

        return -1;
    }

    /**
     * Chiamato quando il robot torna (Sblocco Risorsa)
     */
    public Event startNextJobInQueue(double completionTime) {
        this.serverBusy = false;
        this.numBusyServers = 0;
        this.currentServiceEvent = null;

        if (!waitingQueue.isEmpty()) {
            Event jobB_Event = waitingQueue.poll();
            this.currentServiceEvent = jobB_Event;
            int jobB_Id = jobB_Event.getId();

            Double arrivalTimeJobB = arrivalTimes.get(jobB_Id);
            if (arrivalTimeJobB != null) {
                double waitingTime = completionTime - arrivalTimeJobB;
                waitingTimes.add(waitingTime);
            }

            serviceStartTimes.put(jobB_Id, completionTime);
            startService();
            return jobB_Event;
        }
        return null;
    }

    private void startService() {
        serverBusy = true;
        numBusyServers = 1;
        lastService = getService(0);
        servers.get(0).service += lastService;
        servers.get(0).served++;
        servers.get(0).idle = false;
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
        if (numJobs > 0 && delta >= 0) {
            area[0].node += delta * numJobs;
            area[0].queue += delta * Math.max(0, numJobs - numBusyServers);
        }
        lastUpdateTime = newEvent.getTime();
        currentEvent = newEvent;
    }

    @Override
    public void printStatistics(Statistics globalStatistics) {
        DecimalFormat f = new DecimalFormat("###0.000");

        avgW  = responseTimes.stream().mapToDouble(a -> a).average().orElse(0.0);
        avgWq = waitingTimes.stream().mapToDouble(a -> a).average().orElse(0.0);
        avgS  = serviceTimes.stream().mapToDouble(a -> a).average().orElse(0.0);

        System.out.println("\n  Numero di Job serviti su " + arrivedJob + "............................. = " + globalStatistics.cassaOnlineCompletions);
        System.out.println("  [REALE] Tempo di Risposta Medio del Nodo .......... = " + f.format(avgW));
        System.out.println("  [REALE] Tempo di Attesa Medio in Coda  ............ = " + f.format(avgWq));

        if (globalStatistics.cassaOnlineCompletions > 0) {
            System.out.println("  [AREA]  Tempo di Risposta Medio del Nodo .......... = " + f.format(getAvgWait(0, globalStatistics)));
            System.out.println("  [AREA]  Tempo di Attesa Medio in Coda  ............ = " + f.format(getAvgDelay(0, globalStatistics)));
        }
        System.out.println("  Utilizzazione del Server .......................... = " + f.format(getUtilization(0)));
    }

    @Override public double getAvgInterarrival(int i, Statistics g) { return 0; }
    @Override public double getUtilization(int i) { return (lastDeparture > firstArrive) ? servers.get(0).service / (lastDeparture - firstArrive) : 0; }
    @Override public double getAvgService(int i) { return (servers.get(0).served > 0) ? servers.get(0).service / servers.get(0).served : 0; }
    public Event getCurrentServiceEvent() { return currentServiceEvent; }
    public void resetCurrentServiceEvent() { this.currentServiceEvent = null; }
    public void recordCompletion(int id, double time) {}

    @Override
    public double getAvgNode(int i, Statistics globalStatistics) {
        double batchDuration = lastUpdateTime - batchStartTime;
        return (batchDuration > 0) ? area[0].node / batchDuration : 0;
    }
    @Override
    public double getAvgQueue(int i, Statistics globalStatistics) {
        double batchDuration = lastUpdateTime - batchStartTime;
        return (batchDuration > 0) ? area[0].queue / batchDuration : 0;
    }

    @Override
    public double getAvgWait(int i, Statistics g) {
        double den = g.cassaOnlineCompletions;
        return (den > 0) ? area[0].node / den : 0;
    }
    @Override
    public double getAvgDelay(int i, Statistics g) {
        double den = g.cassaOnlineCompletions;
        return (den > 0) ? area[0].queue / den : 0;
    }

    public void clearBatchData() {
        responseTimes.clear();
        waitingTimes.clear();
        serviceTimes.clear();
    }

    public double getAvgNode2(int i) { return (lastDeparture > firstArrive) ? area[0].node / (lastDeparture - firstArrive) : 0; }
    public double getAvgQueue2(int i) { return (lastDeparture > firstArrive) ? area[0].queue / (lastDeparture - firstArrive) : 0; }
}