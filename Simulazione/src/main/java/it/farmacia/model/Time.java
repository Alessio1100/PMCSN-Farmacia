package it.farmacia.model;

public class Time {

    public double current;      //clock di simulazione corrente
    public double next;         //tempo del prossimo evento
    public double last;         //tempo dell'ultimo arrivo processato

    public Time(double current, double next) {
        this.current = current;
        this.next = next;
    }
}
