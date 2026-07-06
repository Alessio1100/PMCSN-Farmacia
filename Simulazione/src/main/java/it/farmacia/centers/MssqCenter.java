package it.farmacia.centers;

import it.farmacia.events.Event;
import it.farmacia.model.Area;
import it.farmacia.model.Statistics;
import it.farmacia.utils.Rvgs;
import it.farmacia.model.Server;

import java.text.DecimalFormat;
import java.util.*;

public abstract class MssqCenter extends Center {

    public int contaJobCoda = 0;
    public double firstArrive = -1;
    public double lastArrive;
    public double lastDeparture;

    // --- NUOVI CONTATORI PER LE DUE AREE ---
    public int numInInteraction = 0;    // Job in fase di pagamento/interazione
    public int completedInteractions = 0; // Contatore per la media dell'area service

    // Code
    public Queue<Event> waitingQueue = new LinkedList<>();
    public Map<Server, Event> serviceEvents = new HashMap<>();
    boolean a = true;

    // Array di stato server
    public int[] serverStatus;  // 0 = IDLE, 1 = BUSY
    public int[] occupant;
    private int busyCount = 0;
    private int wait = 0;

    // --- STATISTICHE REALI ---
    public Map<Integer, Double> arrivalTimes = new HashMap<>();
    public Map<Integer, Double> serviceStartTimes = new HashMap<>();
    public List<Double> responseTimes = new ArrayList<>();
    public List<Double> waitingTimes = new ArrayList<>();

    private double[] busyAreaPerServer;

    // --- VARIABILI DI CONTROLLO ---
    //public int arrivedJob = 0;
    public int abandonedJobs = 0;
    public int directQueue = 0;
    public int directService = 0;
    public int departingJobs = 0;
    public int extractedFromQueue = 0;
    public Map<Integer, Integer> departingFromQueue = new HashMap<>();

    public MssqCenter(int id, int numServer, Rvgs v, String name) {
        super(id, numServer, v, name);
        this.area = new Area[1];
        this.area[0] = new Area();
        this.serverStatus = new int[numServer];
        this.occupant = new int[numServer];
        this.busyAreaPerServer = new double[numServer];
        Arrays.fill(serverStatus, 0);
        Arrays.fill(occupant, 0);
        Arrays.fill(busyAreaPerServer, 0);
    }

    public abstract double getService(int serverIndex);

    @Override
    public int processArrival() {
        lastArrive = currentEvent.getTime();
        numJobs++; // Incremento per l'area NODO TOTALE
        arrivedJob++;

        if (firstArrive < 0) firstArrive = currentEvent.getTime();

        int jobId = currentEvent.getId();
        arrivalTimes.put(jobId, currentEvent.getTime());

        if (busyCount < servers.size()) {
            int serverIndex = findIdleServer();
            if (serverIndex != -1) {
                if (serverStatus[serverIndex] != 0) {
                    waitingQueue.add(cloneEvent(currentEvent));
                    return -1;
                }

                Server server = servers.get(serverIndex);
                Event job = cloneEvent(currentEvent);
                job.setServer(server);
                serviceEvents.put(server, job);

                directService++;
                serviceStartTimes.put(jobId, currentEvent.getTime());
                waitingTimes.add(0.0);

                // INIZIO INTERAZIONE
                numInInteraction++;

                markServerBusy(currentEvent, serverIndex, jobId, "proc arrival mssq", currentEvent.getTime());
                startService(serverIndex);
                return serverIndex;
            }
        }

        directQueue++;
        departingFromQueue.put(currentEvent.getId(), waitingQueue.size());
        waitingQueue.add(cloneEvent(currentEvent));
        contaJobCoda++;
        return -1;
    }

    @Override
    public int processDeparture() {
        lastDeparture = currentEvent.getTime();

        Server server = currentEvent.getServer();
        int serverIndex = server.id;
        int jobId = currentEvent.getId();
        server.lastDeparture = currentEvent.getTime();

        departingJobs++;
        if (departingFromQueue.containsKey(jobId)) extractedFromQueue++;

        // FINE INTERAZIONE (Il cassiere ha finito, ma il server può restare bloccato)
        if (numInInteraction > 0) numInInteraction--;
        completedInteractions++;

        return -1;
    }

    public boolean removeJobById(int id, double currentTime) {
        Iterator<Event> iterator = waitingQueue.iterator();
        while (iterator.hasNext()) {
            Event event = iterator.next();
            if (event.getId() == id) {
                iterator.remove();
                numJobs--; // Rimosso dal nodo perché ha abbandonato
                abandonedJobs++;
                contaJobCoda--;

                Double arrTime = arrivalTimes.remove(id);
                if (arrTime != null) {
                    waitingTimes.add(currentTime - arrTime);
                }
                serviceStartTimes.remove(id);
                return true;
            }
        }
        return false;
    }

    public void changeConfiguration(String mode, int serverToMod, double time){
        int c=0;
        if(mode.equals("add")){
            for (int i = 0; i < serverStatus.length; i++) {
                if (c < serverToMod && serverStatus[i] == -1) {
                    serverStatus[i] = 0; occupant[i] = 0; c++;
                    //System.out.println("Cassa: "+i+" aggiunta alle disponibili" );
                }
            }
        }
        if(mode.equals("remove")){
            for (int i = 0; i < serverStatus.length; i++) {
                if (c < serverToMod && serverStatus[i] == 0) {
                    serverStatus[i] = -1; occupant[i] = -1; c++;
                    //System.out.println("Cassa: "+i+" rimossa dalle disponibili" );
                }
            }
        }
    }

    public String getClasseFarmacoById(int id) {
        for (Event event : waitingQueue) {
            if (event.getId() == id) return event.getClasseFarmaco();
        }
        return null;
    }

    public Event getServiceEventByServer(Server server) { return serviceEvents.get(server); }
    public void resetServiceEvent(Server server) { serviceEvents.remove(server); }
    public void resetBusyAreas() { if (busyAreaPerServer != null) Arrays.fill(busyAreaPerServer, 0.0); }

    @Override
    public int findIdleServer() {
        int bestServer = -1;
        if (a) {
            for (int i = 0; i < serverStatus.length; i++) {
                if (serverStatus[i] == 0) { bestServer = i; a = false; return bestServer; }
            }
        } else {
            for (int i = serverStatus.length - 1; i >= 0; i--) {
                if (serverStatus[i] == 0) { bestServer = i; a = true; return bestServer; }
            }
        }
        return -1;
    }

    public void markServerBusy(Event event, int serverIndex, int job, String prov, double time) {
        if (prov.equals("extract from completion queue")){
            serviceStartTimes.put(job, time);
            // Inizia interazione estraendo da coda
            numInInteraction++;
        }
        if (occupant[serverIndex] == 0) {
            serverStatus[serverIndex] = 1;
            occupant[serverIndex] = job;
            event.setServer(this.getServer(serverIndex));
            busyCount++;
        }
    }

    public void markServerIdle(int serverIndex, int job, String mode, String prov, double time) {
        if (mode.equals("wait")) { wait++; return; }
        if (wait > 0) { serverStatus[serverIndex] = -1; busyCount--; occupant[serverIndex] = -1; wait--; }

        int targetIndex = -1;
        if (serverIndex >= 0 && serverIndex < serverStatus.length && occupant[serverIndex] == job) { targetIndex = serverIndex; }
        else { for (int i = 0; i < serverStatus.length; i++) { if (occupant[i] == job) { targetIndex = i; break; } } }

        if (targetIndex != -1 && occupant[targetIndex] != 0) {
            if (serviceStartTimes.containsKey(job)) {
                busyAreaPerServer[targetIndex] += time - serviceStartTimes.get(job);
            }

            // Registrazione W reale
            Double arrTime = arrivalTimes.remove(job);
            if (arrTime != null) {
                responseTimes.add(time - arrTime);
            }

            serverStatus[targetIndex] = 0;
            occupant[targetIndex] = 0;
            busyCount--;

            // IL JOB LASCIA IL NODO (decremento unico qui per evitare valori incoerenti)
            if (numJobs > 0) numJobs--;

            completedJobs++;
        }
    }

    private void startService(int serverIndex) {
        Server server = servers.get(serverIndex);
        lastService = getService(serverIndex);
        server.service += lastService;
        server.served++;
        server.idle = false;
    }

    protected Event cloneEvent(Event original) {
        Event clone = new Event(original.getType(), original.getTime());
        clone.setCenter(original.getCenter());
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
        // Delta basato sul tempo dell'evento precedente
        if (currentEvent != null) {
            double delta = newEvent.getTime() - currentEvent.getTime();
            if (delta > 0) {
                // AREA 0: NODO TOTALE (Permanenza totale)
                area[0].node += delta * numJobs;
                // AREA QUEUE: CODA
                area[0].queue += delta * Math.max(0, numJobs - busyCount);
                // AREA SERVICE: INTERAZIONE (Solo tempo di pagamento/servizio puro)
                area[0].service += delta * numInInteraction;
            }
        }
        currentEvent = newEvent;
    }

    // --- STATISTICHE ---

    public double getMinResponseTime() { return responseTimes.stream().mapToDouble(d->d).min().orElse(0.0); }
    public double getMaxResponseTime() { return responseTimes.stream().mapToDouble(d->d).max().orElse(0.0); }
    public double getMinWaitingTime() { return waitingTimes.stream().mapToDouble(d->d).min().orElse(0.0); }
    public double getMaxWaitingTime() { return waitingTimes.stream().mapToDouble(d->d).max().orElse(0.0); }
    public int getCompletedJobsCount() { return responseTimes.size(); }

    public void printStatistics(Statistics globalStatistics) {
        DecimalFormat f = new DecimalFormat("###0.000");
        DecimalFormat g = new DecimalFormat("###0.000");

        double interarrival = getAvgInterarrival(0, globalStatistics);
        System.out.println("\n  Avg Interarrivo (Reale) ........................... = " + f.format(interarrival));

        if (this instanceof Casse) {
            System.out.println("  Totale Clienti Arrivati ........................... = " + arrivedJob);
            System.out.println("  Totale Clienti Serviti ............................ = " + completedJobs);
            System.out.println("  Totale Abbandoni .................................. = " + abandonedJobs);
        } else {
            System.out.println("  Totale Ordini Ricevuti ............................ = " + completedJobs);
        }
        //System.out.println("  [REALE] Response Time Medio del Nodo (W) .......... = " + f.format(getAvgWait(0,globalStatistics)));
        //System.out.println("  [REALE] Tempo di Attesa Medio in Coda (Wq) ........ = " + f.format(getAvgDelay(0,globalStatistics)));

        System.out.println("  [AREA]  Tempo di Risposta (Nodo Totale) ........... = " + f.format(getAvgWait(0, globalStatistics)));
        System.out.println("  [AREA]  Tempo di Attesa Medio in Coda (Lq/λ) ...... = " + f.format(getAvgDelay(0, globalStatistics)));

        System.out.println("\n  Utilizzazione dei Server:");
        for (int s = 0; s < numServer; s++) {
            if (lastDeparture > firstArrive) {
                System.out.print("  Server " + s + ": " + g.format(getUtilization(s)) + " ");
                if (servers.get(s).served > 0) {
                    System.out.println("(Avg Service: " + f.format(busyAreaPerServer[s] / servers.get(s).served) + ")");
                } else {
                    System.out.println("(Avg Service: 0.000)");
                }
            }
        }
    }

    @Override
    public double getUtilization(int i) {
        if (busyAreaPerServer[i] > 0 && lastDeparture > firstArrive) {
            return busyAreaPerServer[i] / (lastDeparture - firstArrive);
        }
        return 0;
    }
    public double getAvgService(int i) {
        if (i < 0 || i >= servers.size()) return 0;
        Server s = servers.get(i);
        return (s.served > 0) ? s.service / s.served : 0.0;
    }

    // AREA (Little's Law: W = L/lambda = Area / Completions)
    // Nota: L'area integrata è già il totale cumulato nel tempo; dividerla per i completati dà il tempo medio speso da un job.
    @Override
    public double getAvgWait(int i, Statistics g) { return (arrivedJob>0)? area[0].node/arrivedJob : 0; }
    @Override
    public double getAvgDelay(int i, Statistics g) { return (arrivedJob>0)? area[0].queue/arrivedJob : 0; }

    @Override
    public double getAvgInterarrival(int i, Statistics g) {
        if (arrivedJob > 1) { return (lastArrive - firstArrive) / (arrivedJob - 1); }
        return 0.0;
    }

    @Override
    public double getAvgNode(int i,Statistics globalStatistics) {
        double avgInterarrival = getAvgInterarrival(0, globalStatistics);
        if (avgInterarrival > 0) {
            return (1.0 / avgInterarrival) * getAvgWait(0, globalStatistics);
        }
        return 0.0;
    }

    @Override
    public double getAvgQueue(int i,Statistics globalStatistics) {
        double avgInterarrival = getAvgInterarrival(0, globalStatistics);
        if (avgInterarrival > 0) {
            return (1.0 / avgInterarrival) * getAvgDelay(0, globalStatistics);
        }
        return 0.0;
    }

    public double getBusyAreaPerServer(int i) {
        return busyAreaPerServer[i];
    }
}