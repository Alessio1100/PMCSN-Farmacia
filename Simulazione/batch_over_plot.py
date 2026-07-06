import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

def _read_series(data_file: str) -> pd.DataFrame:
    """
    Ritorna un DataFrame con colonne: ['sampling', 'tempo'].
    Accetta file con 1 colonna (solo 'tempo') o 2 colonne ('sampling','tempo').
    """
    try:
        # tenta CSV standard (con o senza header)
        df = pd.read_csv(data_file)
        if df.shape[1] == 1:
            # una sola colonna: valori di tempo
            df.columns = ['tempo']
            df['sampling'] = np.arange(len(df))
        else:
            # usa le prime due colonne
            df = df.iloc[:, :2].copy()
            df.columns = ['sampling', 'tempo']
    except Exception:
        # separatore generico (spazi/tab)
        df = pd.read_csv(data_file, sep=r'\s+', header=None)
        if df.shape[1] == 1:
            df.columns = ['tempo']
            df['sampling'] = np.arange(len(df))
        else:
            df = df.iloc[:, :2].copy()
            df.columns = ['sampling', 'tempo']
    return df[['sampling', 'tempo']]

def plot_two_response_times(
        data_file_a: str,
        data_file_b: str,
        label_a: str = "Serie A",
        label_b: str = "Serie B",
        vertical_lines: list | None = None,
        title: str = "Confronto tempo di risposta"
):
    """
    Disegna due serie sovrapposte: A in blu, B in rosso.
    - data_file_* possono avere 1 colonna (tempo) o 2 (sampling, tempo).
    - vertical_lines: lista di x dove tracciare linee verticali (grigie tratteggiate).
    """
    A = _read_series(data_file_a)
    B = _read_series(data_file_b)

    fig = plt.figure(figsize=(12, 8))

    # Serie A (blu) e Serie B (rosso)
    plt.plot(A['sampling'], A['tempo'], '-', linewidth=2, label=label_a, color='blue')
    plt.plot(B['sampling'], B['tempo'], '-', linewidth=2, label=label_b, color='red')

    # Linee verticali opzionali (grigio tratteggiato)
    if vertical_lines:
        for x in vertical_lines:
            plt.axvline(x=x, color='gray', linestyle='--', alpha=0.7, linewidth=1.5)

    # Titoli/assi/griglia
    plt.title(title, fontsize=14, fontweight='bold')
    plt.xlabel('Batch', fontsize=12)
    plt.ylabel('Out Of Stock', fontsize=12)
    plt.grid(True, alpha=0.3)

    # Limiti assi basati su entrambe le serie
    x_max = max(A['sampling'].max(), B['sampling'].max())
    y_min = min(A['tempo'].min(), B['tempo'].min())
    y_max = max(A['tempo'].max(), B['tempo'].max())
    plt.xlim(0, x_max * 1.05)
    plt.ylim(y_min * 0.95, y_max * 1.05)

    # Legenda
    plt.legend()

    plt.tight_layout()
    return fig

# Esempio d'uso
if __name__ == "__main__":
    fig = plot_two_response_times(
        "stats/configuration/Total_outofstock[1,1]Veloce.dat",
        "stats/configuration/Total_outofstock[1,1]Lenta.dat",
        label_a="Config [1,1] Veloce",
        label_b="Config [1,1] Lenta",
        vertical_lines=[10, 30, 50, 70, 90, 100],
        title=" Perdite per Out Of Stock - Configurazione [1,1]"
    )
    plt.show()
    # plt.savefig("confronto_response_time.png", dpi=300, bbox_inches='tight')
