import matplotlib.pyplot as plt
import pandas as pd
import re
import os
import numpy as np

# Nome del file
FILENAME = 'Inventory_configs.dat'

def load_data(filename):
    rows = []
    pattern = r"\{(.*?)\} ; (.*?) ; (.*)"

    if not os.path.exists(filename):
        print(f"File '{filename}' non trovato.")
        return pd.DataFrame()

    with open(filename, 'r') as f:
        for idx, line in enumerate(f):
            line = line.strip()
            if not line: continue
            match = re.search(pattern, line)
            if match:
                try:
                    rows.append({
                        'Id': idx + 1,
                        'Config': match.group(1),
                        'Time': float(match.group(2)),
                        'Cost': float(match.group(3))
                    })
                except ValueError:
                    continue
    return pd.DataFrame(rows)

def get_lower_bound_curve(df, x_col, y_col):
    """Calcola la curva 'migliore' (limite inferiore) per visualizzare il trend pulito"""
    # Ordiniamo per la variabile X
    sorted_df = df.sort_values(x_col)

    # Algoritmo per trovare il 'bordo inferiore' della nuvola di punti
    # Manteniamo un punto solo se il suo Y è minore di tutti gli Y visti finora (logica di Pareto)
    x_vals = []
    y_vals = []

    min_y_so_far = float('inf')

    # Per la curva Time->Cost, scorriamo i tempi crescenti e teniamo il costo minimo
    # Per la curva Cost->Time, scorriamo i costi crescenti e teniamo il tempo minimo
    for _, row in sorted_df.iterrows():
        if row[y_col] < min_y_so_far:
            min_y_so_far = row[y_col]
            x_vals.append(row[x_col])
            y_vals.append(row[y_col])

    return x_vals, y_vals

# --- ESECUZIONE ---
df = load_data(FILENAME)

if not df.empty:
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 12))

    # ---------------------------------------------------------
    # GRAFICO 1: Asse X = Tempo (Crescente) -> Asse Y = Costo
    # ---------------------------------------------------------
    # 1. Punti grigi (tutte le configurazioni)
    ax1.scatter(df['Time'], df['Cost'], color='lightgray', s=20, alpha=0.6, label='Configurazioni (Scatter)')

    # 2. Linea Rossa (Il miglior Costo possibile per quel Tempo)
    #    Questa elimina il "rumore" e mostra il vero andamento.
    bx, by = get_lower_bound_curve(df, 'Time', 'Cost')
    ax1.plot(bx, by, 'r-', linewidth=2.5, label='Frontiera Efficiente (Trend)')

    ax1.set_title('Relazione Tempo -> Costo', fontsize=14, weight='bold')
    ax1.set_xlabel('Tempo di Risposta (s) [Ordinato Crescente]', fontsize=12)
    ax1.set_ylabel('Costo (€)', fontsize=12)
    ax1.grid(True, linestyle='--', alpha=0.5)
    ax1.legend()

    # Invertiamo l'asse X o Y se necessario per logica?
    # Solitamente meno tempo è meglio, meno costo è meglio.
    # Qui vediamo: se voglio MENO tempo (sinistra), quanto mi COSTA (asse Y)?

    # ---------------------------------------------------------
    # GRAFICO 2: Asse X = Costo (Crescente) -> Asse Y = Tempo
    # ---------------------------------------------------------
    # 1. Punti grigi
    ax2.scatter(df['Cost'], df['Time'], color='lightgray', s=20, alpha=0.6, label='Configurazioni (Scatter)')

    # 2. Linea Blu (Il miglior Tempo possibile per quel Costo)
    bx2, by2 = get_lower_bound_curve(df, 'Cost', 'Time')
    ax2.plot(bx2, by2, 'b-', linewidth=2.5, label='Miglior Tempo ottenibile (Trend)')

    ax2.set_title('Relazione Costo -> Tempo', fontsize=14, weight='bold')
    ax2.set_xlabel('Costo Totale (€) [Ordinato Crescente]', fontsize=12)
    ax2.set_ylabel('Tempo di Risposta (s)', fontsize=12)
    ax2.grid(True, linestyle='--', alpha=0.5)
    ax2.legend()

    plt.tight_layout()
    plt.show()
    print("Grafici generati. La linea evidenziata mostra il 'best case' (Frontiera di Pareto) eliminando il rumore delle configurazioni inefficienti.")
else:
    print("Nessun dato trovato in Inventory_configs.dat")