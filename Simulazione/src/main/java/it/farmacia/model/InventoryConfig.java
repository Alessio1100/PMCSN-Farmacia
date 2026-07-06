package it.farmacia.model;

public class InventoryConfig {
    public int classId;
    public int s;
    public int S;
    public int initialLevel;
    public double holdingCost;
    public double shortageCost;
    public double orderCost;

    public InventoryConfig(int classId, int s, int S, int initialLevel,
                           double holdingCost, double shortageCost, double orderCost) {
        this.classId = classId;
        this.s = s;
        this.S = S;
        this.initialLevel = initialLevel;
        this.holdingCost = holdingCost;
        this.shortageCost = shortageCost;
        this.orderCost = orderCost;
    }
}
