package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.SimulationValues;
import it.farmacia.model.Statistics;
import it.farmacia.utils.Rvgs;
import java.text.DecimalFormat;
import java.util.*;

/**
 * PriorityQueueCenter con Statistiche Reali (Liste) separate per priorità.
 */
public abstract class PriorityQueueCenter extends Center {

    public static final int HIGH_PRIORITY = 0;
    public static final int LOW_PRIORITY = 1;

    public InventorySystem inventorySystem;

    // Code FIFO
    protected Queue<Event> highPriorityQueue = new LinkedList<>();
    protected Queue<Event> lowPriorityQueue = new LinkedList<>();

    public boolean serverBusy = false;
    public int currentJobPriority = -1;
    private Event currentServiceEvent;

    // VARIABILI PER COMPATIBILITÀ CON SIMULATION.JAVA (Pubbliche)
    public int directQueue = 0;
    public int directService = 0;
    public int departingJobs = 0;
    public int extractedFromQueue = 0;
    public Map<Integer,Integer> departingFromQueue = new HashMap<>();

    // STATISTICHE REALI (Liste separate per priorità)
    public Map<Integer, Double> arrivalTimes = new HashMap<>();

    public List<Double> responseTimesHigh = new ArrayList<>();
    public List<Double> waitingTimesHigh = new ArrayList<>();

    public List<Double> responseTimesLow = new ArrayList<>();
    public List<Double> waitingTimesLow = new ArrayList<>();

    // Statistiche temporali Area
    public double firstArriveHigh = 0;
    public double firstArriveLow = 0;
    public double lastArriveHigh = 0;
    public double lastArriveLow = 0;
    public double lastDeparture = 0;

    // Tempo BUSY del servente, integrato per priorità (∫ 1{server occupato dalla classe} dt).
    // Base CORRETTA per l'utilizzazione: ≤ tempo trascorso → ρ ≤ 1 (a differenza di
    // service/lastDeparture, che accredita l'intero blocco compound a inizio servizio e può dare ρ>1).
    // busyHigh = tempo speso sui CARICHI (rifornimento), busyLow = sui PRELIEVI cliente.
    public double busyHigh = 0.0;
    public double busyLow  = 0.0;

    // Contatori completamenti
    public int completedHighPriority = 0;
    public int completedLowPriority = 0;

    // FIX 4: lastUpdateTime esposto per reset batch
    private double lastUpdateTime = 0;

    public PriorityQueueCenter(int id, Rvgs v, String name) {
        super(id, 1, v, name);
        this.area = new Area[2];
        this.area[0] = new Area();
        this.area[1] = new Area();
    }

    /**
     * FIX 4: Reset lastUpdateTime al tempo corrente per evitare delta enormi dopo reset batch.
     */
    public void resetLastUpdateTime(double currentTime) {
        this.lastUpdateTime = currentTime;
    }

    // PRIORITÀ INVERTITA (richiesta): al Braccio Uno il PRELIEVO CLIENTE (coda "LOW"/dispatcher) ha
    // priorità sul CARICO/rifornimento (coda "HIGH"/magazziniere). Le etichette HIGH/LOW restano
    // invariate (indici di area e contatori a valle), ma la DISCIPLINA di servizio serve prima la
    // coda dei clienti: così i pochi prelievi instradati a B1 non aspettano dietro i grossi carichi
    // e la loro performance si allinea a quella del Braccio Due.

    /**
     * Processa arrivo coda "HIGH" = CARICO/rifornimento (magazziniere). Ora CEDE la precedenza ai
     * clienti: parte solo se il server è libero E non ci sono prelievi cliente in coda.
     */
    public int processHighPriorityArrival() {
        numJobs++;
        arrivedJob++;

        lastArriveHigh = currentEvent.getTime();
        if (firstArriveHigh == 0) firstArriveHigh = currentEvent.getTime();

        arrivalTimes.put(currentEvent.getId(), currentEvent.getTime());

        if (!serverBusy && lowPriorityQueue.isEmpty()) {
            currentServiceEvent = cloneEvent(currentEvent);
            directService++;
            waitingTimesHigh.add(0.0);
            return startService(HIGH_PRIORITY);
        } else {
            directQueue++;
            departingFromQueue.put(currentEvent.getId(), highPriorityQueue.size());
            highPriorityQueue.add(cloneEvent(currentEvent));
            return -1;
        }
    }

    /**
     * Processa arrivo coda "LOW" = PRELIEVO CLIENTE (dispatcher). Ora è la coda PRIORITARIA: parte
     * appena il server è libero, indipendentemente dai carichi in attesa.
     */
    public int processLowPriorityArrival() {
        numJobs++;
        arrivedJob++;

        lastArriveLow = currentEvent.getTime();
        if (firstArriveLow == 0) firstArriveLow = currentEvent.getTime();

        arrivalTimes.put(currentEvent.getId(), currentEvent.getTime());

        if (!serverBusy) {
            currentServiceEvent = cloneEvent(currentEvent);
            directService++;
            waitingTimesLow.add(0.0);
            return startService(LOW_PRIORITY);
        } else {
            directQueue++;
            departingFromQueue.put(currentEvent.getId(), lowPriorityQueue.size());
            lowPriorityQueue.add(cloneEvent(currentEvent));
            return -1;
        }
    }

    @Override
    public int processArrival() {
        String mittente = currentEvent.getMittente();
        if (mittente == null) mittente = "dispatcher";

        if (mittente.equals("magazziniere")) {
            return processHighPriorityArrival();
        } else if (mittente.equals("dispatcher")) {
            return processLowPriorityArrival();
        } else {
            return processHighPriorityArrival();
        }
    }

    @Override
    public int processDeparture() {
        lastDeparture = currentEvent.getTime();
        // FIX 1: era completedJobs-- (BUG GRAVE), ora è completedJobs++
        completedJobs++;
        numJobs--;
        departingJobs++;

        int jobId = currentEvent.getId();

        // Calcolo RESPONSE TIME del job che esce
        Double arrivalTime = arrivalTimes.remove(jobId);
        if (arrivalTime != null) {
            double response = currentEvent.getTime() - arrivalTime;
            if (currentJobPriority == HIGH_PRIORITY) {
                responseTimesHigh.add(response);
            } else {
                responseTimesLow.add(response);
            }
        }

        if (departingFromQueue.containsKey(jobId)) {
            extractedFromQueue++;
        }

        // Gestione logica inventory / next center
        // NOTA: solo LOW_PRIORITY (prelievo/Dispatcher) viene gestito qui.
        // HIGH_PRIORITY (carico/Magazziniere) è gestito esclusivamente in handleBraccioUno
        // in Simulation.java per evitare doppio conteggio (BraccioUno extends PriorityQueueCenter).
        // NOTA: l'inventario è gestito in Simulation.handleBraccioUno: per i PRELIEVI (LOW) il
        // consumo per-articolo con le K classi dell'ordine (mappa orderClassi); per i RIFORNIMENTI
        // (HIGH) la receiveDelivery delle Q unità. Qui il centro gestisce solo servizio e code.
        serverBusy = false;

        // PRIORITÀ INVERTITA: seleziona prossimo job PRIMA dai PRELIEVI CLIENTE (coda LOW/dispatcher),
        // poi dai CARICHI (coda HIGH/magazziniere). Il cliente ha precedenza sul rifornimento.
        if (!lowPriorityQueue.isEmpty()) {
            currentEvent = lowPriorityQueue.poll();
            currentServiceEvent = cloneEvent(currentEvent);

            Double nextArr = arrivalTimes.get(currentEvent.getId());
            if (nextArr != null) {
                waitingTimesLow.add(lastDeparture - nextArr);
            }

            startService(LOW_PRIORITY);
            return 0;
        }

        if (!highPriorityQueue.isEmpty()) {
            currentEvent = highPriorityQueue.poll();
            currentServiceEvent = cloneEvent(currentEvent);

            Double nextArr = arrivalTimes.get(currentEvent.getId());
            if (nextArr != null) {
                waitingTimesHigh.add(lastDeparture - nextArr);
            }

            startService(HIGH_PRIORITY);
            return 0;
        }

        currentJobPriority = -1;
        return -1;
    }

    private int startService(int priority) {
        serverBusy = true;
        currentJobPriority = priority;
        currentServiceEvent = cloneEvent(currentEvent);
        lastService = getService(0);
        servers.get(0).service += lastService;
        servers.get(0).served++;
        servers.get(0).idle = false;
        numBusyServers = 1;
        return 0;
    }

    private Event cloneEvent(Event original) {
        Event clone = new Event(original.getType(), original.getTime());
        clone.setCenter(original.getCenter());
        clone.setClasseFarmaco(original.getClasseFarmaco());
        clone.setNumeroFarmaciRichiesti(original.getNumeroFarmaciRichiesti());
        clone.setOnline(original.checkOnline());
        clone.setExternal(original.isExternal());
        clone.setId(original.getId());
        clone.setMittente(original.getMittente());
        clone.setServer(original.getServer());
        clone.setFirstArrivalTime(original.getFirstArrivalTime());
        return clone;
    }

    public Event getCurrentServiceEvent() { return currentServiceEvent; }
    public void resetCurrentServiceEvent() { this.currentServiceEvent = null; }

    @Override
    public void updateStatistics(Event newEvent) {
        double delta = newEvent.getTime() - lastUpdateTime;
        if (delta >= 0) {
            int queueHigh = highPriorityQueue.size();
            int queueLow = lowPriorityQueue.size();
            int jobsInSystemHigh = queueHigh + (serverBusy && currentJobPriority == HIGH_PRIORITY ? 1 : 0);
            int jobsInSystemLow = queueLow + (serverBusy && currentJobPriority == LOW_PRIORITY ? 1 : 0);

            area[HIGH_PRIORITY].node += delta * jobsInSystemHigh;
            area[HIGH_PRIORITY].queue += delta * queueHigh;
            area[LOW_PRIORITY].node += delta * jobsInSystemLow;
            area[LOW_PRIORITY].queue += delta * queueLow;

            // Integrale del tempo busy per priorità (stato valido sull'intervallo [lastUpdateTime, now]).
            if (serverBusy) {
                if (currentJobPriority == HIGH_PRIORITY) busyHigh += delta;
                else if (currentJobPriority == LOW_PRIORITY) busyLow += delta;
            }
        }
        lastUpdateTime = newEvent.getTime();
        currentEvent = newEvent;
    }

    @Override
    public void printStatistics(Statistics globalStatistics) {
        DecimalFormat f = new DecimalFormat("###0.000");

        System.out.println("\n  --- HIGH PRIORITY QUEUE ---");
        double avgRespHigh = responseTimesHigh.stream().mapToDouble(a->a).average().orElse(0.0);
        double avgWaitHigh = waitingTimesHigh.stream().mapToDouble(a->a).average().orElse(0.0);
        System.out.println("    Numero di ORDINI di rifornimento (carico) ....... = " + responseTimesHigh.size());
        System.out.println("    [REALE] Tempo di Attesa Medio in Coda ........... = " + f.format(avgWaitHigh));
        System.out.println("    [REALE] Tempo di Risposta Medio del Nodo ........ = " + f.format(avgRespHigh));
        System.out.println("    [AREA]  Tempo di Attesa Medio in Coda ........... = " + f.format(getAvgDelay(HIGH_PRIORITY, globalStatistics)));
        System.out.println("    [AREA]  Tempo di Risposta Medio del Nodo......... = " + f.format(getAvgWait(HIGH_PRIORITY, globalStatistics)));

        System.out.println("\n  --- LOW PRIORITY QUEUE ---");
        double avgRespLow = responseTimesLow.stream().mapToDouble(a->a).average().orElse(0.0);
        double avgWaitLow = waitingTimesLow.stream().mapToDouble(a->a).average().orElse(0.0);
        System.out.println("    Numero di ORDINI serviti (prelievi clienti) ..... = " + responseTimesLow.size());
        System.out.println("    [REALE] Tempo di Attesa Medio in Coda  .......... = " + f.format(avgWaitLow));
        System.out.println("    [REALE] Tempo di Risposta Medio del Nodo ........ = " + f.format(avgRespLow));
        System.out.println("    [AREA]  Tempo di Attesa Medio in Coda  .......... = " + f.format(getAvgDelay(LOW_PRIORITY, globalStatistics)));
        System.out.println("    [AREA]  Tempo di Risposta Medio del Nodo ........ = " + f.format(getAvgWait(LOW_PRIORITY, globalStatistics)));

        System.out.println("\n  Utilizzazione del Server .......................... = " + f.format(getUtilization(0)));
    }

    // NOTA: il denominatore è il numero di ORDINI completati (per priorità). NON si somma più
    // outOfStockBraccioUno: nel modello "ordine = un job" l'OOS è contato PER ARTICOLO dentro un
    // ordine che si completa comunque (un solo passaggio nel centro), mentre completions è
    // per-ordine. Sommare item OOS gonfiava il denominatore e sottostimava W/Wq.
    @Override
    public double getAvgDelay(int p, Statistics g) {
        int completed = (p == HIGH_PRIORITY) ? g.highPriorityCompletions : g.lowPriorityCompletions;
        return (completed > 0) ? area[p].queue / completed : 0;
    }

    @Override
    public double getAvgWait(int p, Statistics g) {
        int completed = (p == HIGH_PRIORITY) ? g.highPriorityCompletions : g.lowPriorityCompletions;
        return (completed > 0) ? area[p].node / completed : 0;
    }

    @Override
    public double getAvgInterarrival(int priority, Statistics g) {
        if (priority == HIGH_PRIORITY) {
            int count = responseTimesHigh.size();
            if (count > 1 && lastArriveHigh > firstArriveHigh) {
                return (lastArriveHigh - firstArriveHigh) / (count - 1);
            }
        } else if (priority == LOW_PRIORITY) {
            int count = responseTimesLow.size();
            if (count > 1 && lastArriveLow > firstArriveLow) {
                return (lastArriveLow - firstArriveLow) / (count - 1);
            }
        }
        return 0.0;
    }

    @Override
    public double getAvgNode(int priority, Statistics globalStatistics) {
        double avgInterarrival = getAvgInterarrival(priority, globalStatistics);
        if (avgInterarrival > 0) {
            return (1.0 / avgInterarrival) * getAvgWait(priority, globalStatistics);
        }
        return 0.0;
    }

    @Override
    public double getAvgQueue(int priority, Statistics globalStatistics) {
        double avgInterarrival = getAvgInterarrival(priority, globalStatistics);
        if (avgInterarrival > 0) {
            return (1.0 / avgInterarrival) * getAvgDelay(priority, globalStatistics);
        }
        return 0.0;
    }

    // Utilizzazione basata sul TEMPO BUSY integrato (ρ ≤ 1). Denominatore = orizzonte trascorso
    // (lastUpdateTime = ultimo evento processato).
    @Override
    public double getUtilization(int i) {
        return (lastUpdateTime > 0) ? (busyHigh + busyLow) / lastUpdateTime : 0;
    }

    /** ρ del solo CARICO (rifornimento, coda HIGH). */
    public double getUtilizationHigh() {
        return (lastUpdateTime > 0) ? busyHigh / lastUpdateTime : 0;
    }

    /** ρ dei soli PRELIEVI cliente (coda LOW). */
    public double getUtilizationLow() {
        return (lastUpdateTime > 0) ? busyLow / lastUpdateTime : 0;
    }

    @Override
    public double getAvgService(int i) {
        return (servers.get(0).served > 0) ? servers.get(0).service / servers.get(0).served : 0;
    }

    public abstract double getArrival();
    public int getHighPriorityQueueSize() { return highPriorityQueue.size(); }
    public int getLowPriorityQueueSize() { return lowPriorityQueue.size(); }
}