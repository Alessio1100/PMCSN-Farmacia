package it.farmacia.events;

public enum EventType {
    ARRIVAL, DEPARTURE, ABANDON, TIMECHANGE, SLOTCHANGE, SAMPLING, COMPLETION,
    // Carico INCREMENTALE del rifornimento: micro-evento che deposita 1 unità a banco durante la
    // finestra di servizio del Braccio Uno (vedi Simulation.scheduleIncrementalRestock). È un evento
    // di sistema/inventario, senza centro né coda: NON tocca il servizio del braccio.
    RESTOCK
}
