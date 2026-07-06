import matplotlib.pyplot as plt
import numpy as np

def plot_shortage_costs(filename='Shortage.dat'):
    """
    Legge il file contenente i costi di shortage e crea un grafico.

    Parameters:
    filename (str): Nome del file contenente i dati di shortage cost

    Returns:
    None: Mostra il grafico
    """
    try:
        # Leggi i dati dal file
        with open(filename, 'r') as file:
            # Leggi tutte le righe e converti in float
            costs = []
            for line in file:
                line = line.strip()
                if line:  # Salta righe vuote
                    try:
                        cost = float(line)
                        costs.append(cost)
                    except ValueError:
                        print(f"Attenzione: impossibile convertire '{line}' in numero")

        if not costs:
            print(f"Nessun dato valido trovato nel file {filename}")
            return

        # Crea gli indici delle configurazioni (da 0 a n-1)
        configurations = list(range(len(costs)))

        # Crea il grafico
        plt.figure(figsize=(12, 6))
        plt.plot(configurations, costs, 'bo', markersize=4)

        # Personalizza il grafico
        plt.xlabel('Indice Configurazione')
        plt.ylabel('System Cost')
        plt.title('Total System Cost per Configurazione del Sistema di Inventario')
        plt.grid(True, alpha=0.3)

        # Aggiungi informazioni statistiche
        mean_cost = np.mean(costs)
        min_cost = min(costs)
        max_cost = max(costs)

        plt.axhline(y=mean_cost, color='r', linestyle='--', alpha=0.7,
                    label=f'Costo Medio: {mean_cost:.2f}')

        # Evidenzia il punto con costo minimo
        min_idx = costs.index(min_cost)
        plt.plot(min_idx, min_cost, 'go', markersize=8,
                 label=f'Min Cost: {min_cost:.2f} (Config {min_idx})')

        plt.legend()
        plt.tight_layout()

        # Mostra statistiche nella console
        print(f"Statistiche Shortage Costs:")
        print(f"- Numero configurazioni: {len(costs)}")
        print(f"- Costo minimo: {min_cost:.2f} (Configurazione {min_idx})")
        print(f"- Costo massimo: {max_cost:.2f}")
        print(f"- Costo medio: {mean_cost:.2f}")
        print(f"- Deviazione standard: {np.std(costs):.2f}")

        plt.show()

    except FileNotFoundError:
        print(f"Errore: File '{filename}' non trovato")
    except Exception as e:
        print(f"Errore durante la lettura del file: {e}")

# Esempio di utilizzo
if __name__ == "__main__":
    plot_shortage_costs('TotalCost_range_5.dat')

    # Se vuoi salvare il grafico invece di mostrarlo:
    # plt.savefig('shortage_costs.png', dpi=300, bbox_inches='tight')
    # plt.close()