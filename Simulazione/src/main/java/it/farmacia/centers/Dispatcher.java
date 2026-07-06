package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.Statistics;
import it.farmacia.model.StreamType;
import it.farmacia.utils.Rvgs;

import java.text.DecimalFormat;
import java.util.*;

public class Dispatcher extends Center {

    // FIX 3: rimosso "private" da firstArrive e lastArrive
    // per permettere il reset in resetBatchStatistics() di Simulation.java
    double firstArrive;
    double lastArrive;
    public double lastDeparture;
    private Queue<Event> waitingQueue = new LinkedList<>();
    public boolean serverBusy = false;
    private Event currentServiceEvent;

    // STATISTICHE REALI
    public List<Double> responseTimes = new ArrayList<>();
    public List<Double> waitingTimes = new ArrayList<>();
    public Map<Integer, Double> arrivalTimes = new HashMap<>();

    // FIX 4: lastUpdateTime esposto per reset batch
    private double lastUpdateTime = 0;

    public Dispatcher(Rvgs v) {
        super(3, 1, v, "Dispatcher");
        this.area = new Area[1];
        this.area[0] = new Area();
    }

    /**
     * FIX 4: Reset lastUpdateTime al tempo corrente per evitare delta enormi dopo reset batch.
     */
    public void resetLastUpdateTime(double currentTime) {
        this.lastUpdateTime = currentTime;
    }

    @Override
    public int getNextCenter() {
        return 0; // Routing gestito esternamente
    }

    @Override
    public double getService(int serverIndex) {
        v.rngs.selectStream(StreamType.STREAM_SERVICE_DISPATCHER);
        return v.exponential(3);
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
            waitingTimes.add(0.0);
            startService();
            return 0;
        } else {
            waitingQueue.add(cloneEvent(currentEvent));
            return -1;
        }
    }

    @Override
    public int processDeparture() {
        lastDeparture = currentEvent.getTime();
        completedJobs++;
        numJobs--;

        Double arrivalTime = arrivalTimes.remove(currentEvent.getId());
        if (arrivalTime != null) {
            responseTimes.add(currentEvent.getTime() - arrivalTime);
        }

        serverBusy = false;

        if (!waitingQueue.isEmpty()) {
            currentServiceEvent = waitingQueue.poll();

            Double nextArr = arrivalTimes.get(currentServiceEvent.getId());
            if (nextArr != null) {
                waitingTimes.add(currentEvent.getTime() - nextArr);
            }

            startService();
            return 0;
        }

        return -1;
    }

    private void startService() {
        serverBusy = true;
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

        double avgResp = responseTimes.stream().mapToDouble(a->a).average().orElse(0.0);
        double avgWait = waitingTimes.stream().mapToDouble(a->a).average().orElse(0.0);
        System.out.println("\n  Numero di Job serviti su " + arrivedJob + "............................. = " + responseTimes.size());
        System.out.println("  [REALE] Tempo di Risposta Medio del Nodo .......... = " + f.format(avgResp));
        System.out.println("  [REALE] Tempo di Attesa Medio in Coda  ............ = " + f.format(avgWait));
        System.out.println("  [AREA]  Tempo di Risposta Medio del Nodo .......... = " + f.format(getAvgWait(0, globalStatistics)));
        System.out.println("  [AREA]  Tempo di Attesa Medio in Coda  ............ = " + f.format(getAvgDelay(0, globalStatistics)));
        System.out.println("  Utilizzazione del Server .......................... = " + f.format(getUtilization(0)));
    }

    @Override public double getAvgInterarrival(int i, Statistics g) { return 0; }
    @Override public double getUtilization(int i) { return (lastDeparture > firstArrive) ? servers.get(0).service / (lastDeparture - firstArrive) : 0; }
    @Override public double getAvgService(int i) { return (servers.get(0).served > 0) ? servers.get(0).service / servers.get(0).served : 0; }
    public Event getCurrentServiceEvent() { return currentServiceEvent; }
    public void resetCurrentServiceEvent() { this.currentServiceEvent = null; }

    @Override
    public double getAvgNode(int i, Statistics globalStatistics) { return (lastDeparture > firstArrive) ? area[0].node / (lastDeparture - firstArrive) : 0; }
    @Override
    public double getAvgQueue(int i, Statistics globalStatistics) { return (lastDeparture > firstArrive) ? area[0].queue / (lastDeparture - firstArrive) : 0; }

    @Override
    public double getAvgWait(int i, Statistics g) {
        double den = g.dispatcherCompletions;
        return (den > 0) ? area[0].node / den : 0;
    }
    @Override
    public double getAvgDelay(int i, Statistics g) {
        double den = g.dispatcherCompletions;
        return (den > 0) ? area[0].queue / den : 0;
    }

    public void clearBatchData() {
        responseTimes.clear();
        waitingTimes.clear();
    }

    public double getAvgNode2(int i) { return (lastDeparture > firstArrive) ? area[0].node / (lastDeparture - firstArrive) : 0; }
    public double getAvgQueue2(int i) { return (lastDeparture > firstArrive) ? area[0].queue / (lastDeparture - firstArrive) : 0; }
}