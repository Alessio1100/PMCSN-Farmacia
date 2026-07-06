package it.farmacia.events;

import it.farmacia.centers.Center;
import it.farmacia.model.Server;

public class Event {
    private EventType type;     //tipologia dell'evento (arrivo, completamento...)
    private double time;        //tempo di simulazione in cui avviene l'evento
    private Center center;      //centro a cui è destinato l'evento
    private Server server;      //server di quel centro a cui è destinato l'evento (in caso di completamento)
    private boolean external = false;   //tiene traccia per gli eventi di arrivo se sono esterni o conseguenti a un completamento
    private boolean isOnline = false ;
    private int id;
    private String mittente;
    private String classeFarmaco;
    private int numeroFarmaciRichiesti;
    private int originalCassaId ; // ID della cassa di origine
    private double firstArrivalTime = 0.0;

    public Event(EventType type, double time) {
        this.type = type;
        this.time = time;
        this.mittente = null;
        this.classeFarmaco = null;
        this.numeroFarmaciRichiesti = 0;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public Center getCenter() {
        return center;
    }

    public void setCenter(Center center) {
        this.center = center;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public int getNumeroFarmaciRichiesti() {
        return numeroFarmaciRichiesti;
    }

    public void setNumeroFarmaciRichiesti(int numero) {
        this.numeroFarmaciRichiesti = numero;
    }

    public void setOnline(boolean isOnline) { this.isOnline = isOnline; }

    public boolean checkOnline() {return isOnline;}

    public void setId(int id){this.id = id;}

    public int getId(){return id;}

    public String getClasseFarmaco() {return classeFarmaco;}

    public void setClasseFarmaco(String classeFarmaco) { this.classeFarmaco = classeFarmaco;}

    public void setMittente(String mittente) {this.mittente = mittente;}

    public String getMittente() { return mittente;
    }
    public int getOriginalCassaId() { return originalCassaId; }

    public void setOriginalCassaId(int cassaId) { this.originalCassaId = cassaId; }

    public double getFirstArrivalTime() {
        return firstArrivalTime;
    }

    public void setFirstArrivalTime(double time) {
        this.firstArrivalTime = time;
    }

}
