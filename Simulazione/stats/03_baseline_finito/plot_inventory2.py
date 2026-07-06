"""
plot_inventory.py
-----------------
Legge i file <dir>/inventory_class{n}_level.dat prodotti da InventorySystem.writeLevelTimeSeries()
e genera il grafico combinato dello stato dell'inventario per ogni estrazione.

Formato file (3 colonne tab-separated, 2 righe di header con #):
  # s={s_val} S={S_val}
  # time_s    level   cum_oos
  0.000       70      0
  ...

Produce un unico file con tutte le classi sovrapposte a colori diversi
(solo pannello dei livelli di scorta).

Uso:
    python plot_inventory.py                 # usa ./dat -> ./grafici (relativo allo script)
    python plot_inventory.py --show          # mostra a schermo oltre a salvare
    python plot_inventory.py --dir X --out Y --fmt pdf
"""

import argparse
import os
import re
import glob
import sys
import numpy as np
import matplotlib.pyplot as plt

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

CLASS_NAMES = {
    1: "Classe 1 (ricetta)",
    2: "Classe 2 (OTC)",
    3: "Classe 3 (integratori)",
    4: "Classe 4 (dispositivi)",
    5: "Classe 5 (galeniche)",
}
COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e"]


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--dir", default=os.path.join(SCRIPT_DIR, "dat"),
                   help="Cartella con i .dat (default: ./dat accanto allo script)")
    p.add_argument("--out", default=os.path.join(SCRIPT_DIR, "grafici"),
                   help="Cartella output dei grafici (default: ./grafici)")
    p.add_argument("--fmt", default="png")
    p.add_argument("--dpi", default=150, type=int)
    p.add_argument("--show", action="store_true", default=False,
                   help="Mostra i grafici a schermo (di default li salva soltanto)")
    p.add_argument("--max-points", default=6000, type=int,
                   help="Decimazione: max punti per curva (conserva i cambi)")
    return p.parse_args()


# ── Caricamento ───────────────────────────────────────────────────────────────

def load_level_dat(path):
    """Ritorna (s_val, S_val, times, levels, cum_oos). cum_oos=0 se file a 2 colonne."""
    s_val = S_val = None
    times, levels, oos = [], [], []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                m = re.search(r"s=(\d+)\s+S=(\d+)", line)
                if m:
                    s_val, S_val = int(m.group(1)), int(m.group(2))
                continue
            parts = line.replace(",", ".").split()
            if len(parts) >= 2:
                try:
                    times.append(float(parts[0]))
                    levels.append(int(float(parts[1])))
                    oos.append(int(float(parts[2])) if len(parts) >= 3 else 0)
                except ValueError:
                    continue
    return (s_val, S_val,
            np.array(times), np.array(levels, dtype=int), np.array(oos, dtype=int))


def decimate_idx(times, levels, oos, max_points):
    """Indici decimati conservando ogni cambio di livello o di OOS (eventi importanti)."""
    n = len(times)
    if n <= max_points:
        return np.arange(n)
    changes = np.where((np.diff(levels) != 0) | (np.diff(oos) != 0))[0] + 1
    step = max(1, n // max_points)
    uniform = np.arange(0, n, step)
    return np.unique(np.concatenate([[0, n - 1], changes, uniform]))


# ── Vista COMBINATA (tutte le classi sovrapposte) ─────────────────────────────

def plot_combined(data, out_path, fmt, dpi, show, max_points):
    fig, ax = plt.subplots(figsize=(13, 6))

    for (class_id, s_val, S_val, times, levels, oos) in data:
        idx = decimate_idx(times, levels, oos, max_points)
        th = times[idx] / 3600.0
        color = COLORS[(class_id - 1) % len(COLORS)]
        name = CLASS_NAMES.get(class_id, f"Classe {class_id}")

        # Grafico del livello (dente di sega)
        ax.step(th, levels[idx], where="post", color=color, linewidth=1.2, alpha=0.85, label=name)

    ax.set_xlabel("Tempo (ore)", fontsize=11)
    ax.set_ylabel("Livello scorta", fontsize=11)
    ax.set_ylim(0)
    ax.set_title("Inventario — tutte le classi (livello l(t))", fontsize=12, pad=8)
    ax.grid(True, linewidth=0.4, alpha=0.5)
    ax.legend(fontsize=9, ncol=3, framealpha=0.9, edgecolor="gray", loc="upper right")

    _save(fig, out_path, dpi, show)


def _save(fig, out_path, dpi, show):
    fig.tight_layout()
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    fig.savefig(out_path, dpi=dpi, bbox_inches="tight")
    print(f"  Salvato: {out_path}")
    if show:
        plt.show()
    plt.close(fig)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    files = sorted(glob.glob(os.path.join(args.dir, "inventory_class*_level.dat")))
    if not files:
        print(f"[ERRORE] Nessun inventory_class*_level.dat in '{args.dir}'.")
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    all_data = []

    for fpath in files:
        m = re.search(r"inventory_class(\d+)_level", os.path.basename(fpath))
        class_id = int(m.group(1)) if m else 0
        s_val, S_val, times, levels, oos = load_level_dat(fpath)
        if len(times) == 0:
            print(f"  [WARN] vuoto: {fpath}")
            continue
        print(f"  Classe {class_id} caricata: {len(times)} estrazioni, {times[-1]/3600:.1f}h")

        all_data.append((class_id, s_val, S_val, times, levels, oos))

    # COMBINATO
    if all_data:
        out_comb = os.path.join(args.out, f"inventory_combinato.{args.fmt}")
        plot_combined(all_data, out_comb, args.fmt, args.dpi, args.show, args.max_points)

    print("\nFatto. (Risultato: inventory_combinato.*)")


if __name__ == "__main__":
    main()