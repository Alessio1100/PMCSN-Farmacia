package it.farmacia.model;

import it.farmacia.events.Event;

public class Server {
    public int id;                             //identificativo univoco del server in un certo centro (da 0 a numServer-1)
    public boolean idle = true;
    public double service = 0;                 //tempo totale di servizio svolto dal server
    public long served = 0;                    //numero di job serviti
    public double lastDeparture = 0;           //tempo dell'ultima partenza di un job dal server
    public Event lastEvent;                    //l'ultimo evento processato da quel server
    public boolean active = true;              //se il server è attivo può prendere in carico job

    public Server(int id) {
        this.id = id;
    }
}