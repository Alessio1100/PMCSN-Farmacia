import numpy as np
import matplotlib.pyplot as plt
from pathlib import Path

def running_average(y: np.ndarray) -> np.ndarray:
    y = np.asarray(y, dtype=float)
    return np.cumsum(y) / np.arange(1, len(y) + 1)

# --- file (.dat) da plottare ---
files = ["Casse_response.dat"]
labels = [Path(f).stem for f in files]

# Carica e tronca alla lunghezza minima
series = [np.loadtxt(f, dtype=float, ndmin=1) for f in files]
min_len = min(len(s) for s in series)
series = [s[:min_len] for s in series]
vals = np.vstack(series)

# Min/Max globali (su tutte le serie)
y_min = float(vals.min())
y_max = float(vals.max())

# Dove avvengono (file e posizione 1-based)
r_min, c_min = np.unravel_index(vals.argmin(), vals.shape)
r_max, c_max = np.unravel_index(vals.argmax(), vals.shape)
print(f"Min globale: {y_min:.6f} (file: {labels[r_min]}, pos: {c_min+1})")
print(f"Max globale: {y_max:.6f} (file: {labels[r_max]}, pos: {c_max+1})")
# Medie cumulative per curva
avgs = [running_average(s) for s in series]
avg_global = np.mean(np.vstack(avgs), axis=0)  # media tra le tre medie cumulative
max=0

# Asse X
x = np.arange(1, min_len + 1)

# --- MEDIA per la retta orizzontale (su tutti i valori disponibili) ---
y_mean = float(np.mean(np.vstack(series)))   # unica media complessiva
# (equivale anche a float(avg_global[-1]) perché le serie hanno stessa lunghezza)

# (opzionale) linee verticali
verticali = [12, 30, 60, 80, 96]
verticali = [v for v in verticali if 1 <= v <= x[-1]]

# Plot
plt.figure(figsize=(6, 5), dpi=150)
for y_avg, lab in zip(avgs, labels):
    plt.plot(x, y_avg, linewidth=1.5, label=lab)

# Retta orizzontale sulla media
plt.axhline(y_mean, color="black", linewidth=1.2, label=f"y = media = {y_mean:.2f}")



plt.title("Tempo di risposta – Media cumulativa")
plt.xlabel("Sampling (numero di job)")
plt.ylabel("E(Ts)")
plt.grid(True, which="both", linestyle='-', alpha=0.2)
plt.legend(title="Serie")
plt.tight_layout()
plt.savefig("grafico_media_cumulativa.png")
plt.show()
