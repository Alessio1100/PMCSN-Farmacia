package it.farmacia.model;

public class Order {
    private int classId;
    private int quantity;
    private double orderTime;
    private double deliveryTime; // sarà impostato quando l'item viene consegnato

    public Order(int classId, int quantity, double orderTime) {
        this.classId = classId;
        this.quantity = quantity;
        this.orderTime = orderTime;
        this.deliveryTime = -1; // non ancora consegnato
    }

    // Getters
    public int getClassId() { return classId; }
    public int getQuantity() { return quantity; }
    public double getOrderTime() { return orderTime; }
    public double getDeliveryTime() { return deliveryTime; }

    // Metodo chiamato quando l'item viene consegnato
    public void setDelivered(double deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    // Calcola il delivery lag effettivo
    public double getDeliveryLag() {
        if (deliveryTime < 0) return -1; // non ancora consegnato
        return deliveryTime - orderTime;
    }

    @Override
    public String toString() {
        return String.format("Order{class=%d, qty=%d, orderTime=%.2f, deliveryTime=%.2f}",
                classId, quantity, orderTime, deliveryTime);
    }
}