import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

def plot_response_time(data_file, vertical_lines=None, title="Tempo di risposta - Biglietteria automatica"):
    """
    Crea un grafico del tempo di risposta simile a quello mostrato nell'immagine.

    Parameters:
    data_file (str): Path al file contenente i dati (formato: sampling, tempo)
    vertical_lines (list): Lista di posizioni x per le linee verticali rosse (opzionale)
    title (str): Titolo del grafico
    """

    # Leggi i dati dal file
    # Il file può essere in diversi formati (CSV, TXT con separatori vari)
    try:
        # Prova prima con CSV
        data = pd.read_csv(data_file)
        if len(data.columns) == 1:
            # Se ha una sola colonna, potrebbe essere separato da spazi o tab
            data = pd.read_csv(data_file, delimiter='\s+', header=None)
            data.columns = ['sampling', 'tempo'] if len(data.columns) == 2 else ['tempo']
    except:
        # Se fallisce, prova con separatore generico
        data = pd.read_csv(data_file, delimiter='\s+', header=None)
        data.columns = ['sampling', 'tempo'] if len(data.columns) == 2 else ['tempo']

    # Se il file contiene solo i valori di tempo, crea l'indice sampling
    if len(data.columns) == 1:
        data.columns = ['tempo']
        data['sampling'] = range(len(data))

    # Crea il grafico
    plt.figure(figsize=(12, 8))

    # Plot della linea principale
    plt.plot(data['sampling'], data['tempo'], 'b-', linewidth=2, marker='o', markersize=4)

    # Aggiungi linee verticali rosse tratteggiate se specificate
    if vertical_lines is not None:
        for x_pos in vertical_lines:
            plt.axvline(x=x_pos, color='red', linestyle='--', alpha=0.7, linewidth=1.5)
    else:
        # Linee verticali di default basate sui dati (esempio automatico)
        # Puoi personalizzare questa logica in base alle tue esigenze
        max_sampling = data['sampling'].max()
        default_lines = [10, 30, 50, 70, 90, 100]  # Esempio
        for x_pos in default_lines:
            if x_pos <= max_sampling:
                plt.axvline(x=x_pos, color='red', linestyle='--', alpha=0.7, linewidth=1.5)

    # Personalizzazione del grafico
    plt.title(title, fontsize=14, fontweight='bold')
    plt.xlabel('Sampling', fontsize=12)
    plt.ylabel('ETB', fontsize=12)
    plt.grid(True, alpha=0.3)

    # Imposta i limiti degli assi
    plt.xlim(0, data['sampling'].max() * 1.05)
    plt.ylim(data['tempo'].min() * 0.95, data['tempo'].max() * 1.05)

    # Migliora l'aspetto
    plt.tight_layout()

    return plt.gcf()


# Esempio di utilizzo
if __name__ == "__main__":
    # Usa i tuoi dati
    vertical_positions = [10, 30, 50, 70, 90, 100]  # Personalizza le posizioni delle linee rosse

    fig = plot_response_time("BraccioDue_arrival.dat",  # Sostituisci con il path del tuo file
                             vertical_lines=vertical_positions,
                             title="Average Wait - Braccio Uno")

    plt.show()

    # Salva il grafico se necessario
    # plt.savefig("response_time_plot.png", dpi=300, bbox_inches='tight')