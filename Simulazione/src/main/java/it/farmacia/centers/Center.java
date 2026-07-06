package it.farmacia.centers;

import it.farmacia.model.Server;
import it.farmacia.model.Statistics;
import it.farmacia.utils.Rvgs;
import it.farmacia.events.Event;
import it.farmacia.utils.*;
import it.farmacia.model.Area;
import java.util.ArrayList;
import java.util.List;

public abstract class Center {

    public final int ID;                //identificativo univoco del centro
    public String name;                 //nome del centro
    public int numServer;               //numero di servers nel centro
    public int numJobs = 0;             //numero di jobs attualmente nel centro (servers + coda)
    public int completedJobs = 0;       //numero di jobs processati dal server
    public Event currentEvent;          //l'evento correntemente processato dal centro
    public Area[] area;
    public double lastService;          //ultimo tempo di servizio generato
    public Rvgs v;
    public int arrivedJob = 0;
    public List<Server> servers;            //lista di server del centro
    public int numBusyServers = 0;      //numero di server occupati del centro
    public int numServerToRemove = 0;   //quando questa variabile è != 0, il server presso cui un job è stato appena completato verrà rimosso
    Rvms rvms;


    public Center(int id, int numServer, Rvgs v, String name) {
        ID = id;
        this.name = name;
        this.numServer = numServer;         //inizializza il numero di server per il centro
        this.v = v;
        this.servers = new ArrayList<>();
        for (int i = 0; i < numServer; i++) {
            Server newServer = new Server(i);
            newServer.active = true;
            servers.add(newServer);
        }
        rvms = new Rvms();
    }

    //ritorna il centro successivo (il raggiungimento del centro successivo può essere probabilistico)
    public abstract int getNextCenter();

    public abstract double getService(int serverIndex);

    //processa l'arrivo di un job nel centro
    public abstract int processArrival();

    public Event getCurrentServiceEvent() {
        return currentEvent;  // Di default restituisce currentEvent
    }

    //processa il completamento di un job nel centro
    //ritorna valore != -1 se è presente un ulteriore job in coda da processare
    public abstract int processDeparture();

    //aggiorna le statistiche per il centro
    public abstract void updateStatistics(Event newEvent);

    public abstract void printStatistics(Statistics globalStatistics);

    //quando questa funzione viene invocata, è già stata controllata la presenza di almeno un server libero
    //seleziona tra i server liberi, quello libero da più tempo
    protected int findIdleServer() {
        int bestServer = -1;
        double earliestLastDeparture = Double.MAX_VALUE;

        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            if (server.idle) {
                // Trova il server idle da più tempo (lastDeparture più piccolo)
                if (server.lastDeparture < earliestLastDeparture) {
                    earliestLastDeparture = server.lastDeparture;
                    bestServer = i;
                }
            }
        }

        return bestServer; // Ritorna -1 se nessun server è idle
    }
    public Server getServer(int id) {return servers.get(id);}
    public abstract double getAvgInterarrival(int i,Statistics globalStatistics);

    public abstract double getAvgWait(int i,Statistics globalStatistics);

    public abstract double getAvgDelay(int i,Statistics globalStatistics);

    public abstract double getAvgNode(int i,Statistics globalStatistics);

    public abstract double getAvgQueue(int i,Statistics globalStatistics);

    //ritorna l'utilizzazione dell'i-esimo server del centro
    public abstract double getUtilization(int i);

    public abstract double getAvgService(int i);
}

