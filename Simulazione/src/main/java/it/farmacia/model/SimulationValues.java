package it.farmacia.model;

public class SimulationValues {

    public static int getClassIdFromName(String name) {
        switch (name.toLowerCase()) {
            case "farmaci con ricetta": return 1;
            case "farmaci da banco (sop/otc)": return 2;
            case "integratori": return 3;
            case "dispositivi medici": return 4;
            case "preparazioni galeniche": return 5;
            default: return -1;
        }
    }

    public static String getNameFromClasseId(int classId) {
        switch (classId) {
            case 1: return "Farmaci con ricetta";
            case 2: return "Farmaci da banco (SOP/OTC)";
            case 3: return "Integratori";
            case 4: return "Dispositivi medici";
            case 5: return "Preparazioni galeniche";
            default: return "";
        }
    }

}
