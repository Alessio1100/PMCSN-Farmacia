package it.farmacia.model;

public class Area {
    public double node;                   /* time integrated number in the node  */
    public double queue;                  /* time integrated number in the queue */
    public double service;                /* time integrated number in service   */

    public Area() {
        node = queue = service = 0;
    }
}