"""
plot_inventory.py
-----------------
Legge i file <dir>/inventory_class{n}_level.dat prodotti da InventorySystem.writeLevelTimeSeries()
e genera i grafici dello stato dell'inventario e degli OUT-OF-STOCK per ogni estrazione.

Formato file (3 colonne tab-separated, 2 righe di header con #):
  # s={s_val} S={S_val}
  # time_s    level   cum_oos
  0.000       70      0
  647.714     69      0
  ...
  44446.644   0       17

Ogni riga = una estrazione/evento inventario: 'level' = scorta dopo l'evento,
'cum_oos' = numero cumulato di richieste perse (out-of-stock) di quella classe.

Produce SEMPRE entrambe le viste (così si sceglie quale tenere):
  - SINGOLI:   un file per classe, 2 pannelli (livello l(t) con soglie s/S + OOS cumulati).
  - COMBINATO: un unico file con tutte le classi sovrapposte a colori diversi
               (pannello livelli + pannello OOS cumulati).

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


# ── Vista SINGOLA (una classe) ────────────────────────────────────────────────

def plot_class(class_id, s_val, S_val, times, levels, oos, out_path, fmt, dpi, show, max_points):
    idx = decimate_idx(times, levels, oos, max_points)
    th = times[idx] / 3600.0
    lv = levels[idx]
    oo = oos[idx]
    color = COLORS[(class_id - 1) % len(COLORS)]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 6), sharex=True,
                                   gridspec_kw={"height_ratios": [2, 1]})

    # Pannello livello (dente di sega)
    ax1.step(th, lv, where="post", color=color, linewidth=1.2, label="Livello l(t)")
    if s_val is not None:
        ax1.axhline(s_val, color="#d62728", linewidth=1.1, linestyle="--", label=f"s = {s_val}")
    if S_val is not None:
        ax1.axhline(S_val, color="#2ca02c", linewidth=1.1, linestyle=":", label=f"S = {S_val}")
    ax1.fill_between(th, 0, lv, where=(lv == 0), step="post", color="#d62728", alpha=0.15)
    ax1.set_ylabel("Livello scorta", fontsize=11)
    ax1.set_ylim(0)
    ax1.set_title(f"Inventario — {CLASS_NAMES.get(class_id, f'Classe {class_id}')}  "
                  f"(s={s_val}, S={S_val})  —  OOS totali = {int(oos[-1]) if len(oos) else 0}",
                  fontsize=12, pad=8)
    ax1.grid(True, linewidth=0.4, alpha=0.5)
    ax1.legend(fontsize=9, framealpha=0.9, edgecolor="gray", loc="upper right")

    # Pannello OOS cumulati (gradino crescente)
    ax2.step(th, oo, where="post", color="#d62728", linewidth=1.4, label="OOS cumulati")
    ax2.set_xlabel("Tempo (ore)", fontsize=11)
    ax2.set_ylabel("OOS cumulati", fontsize=11)
    ax2.set_ylim(0)
    ax2.grid(True, linewidth=0.4, alpha=0.5)
    ax2.legend(fontsize=9, framealpha=0.9, edgecolor="gray", loc="upper left")

    _save(fig, out_path, dpi, show)


# ── Vista COMBINATA (tutte le classi sovrapposte) ─────────────────────────────

def plot_combined(data, out_path, fmt, dpi, show, max_points):
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(13, 7), sharex=True,
                                   gridspec_kw={"height_ratios": [2, 1]})

    for (class_id, s_val, S_val, times, levels, oos) in data:
        idx = decimate_idx(times, levels, oos, max_points)
        th = times[idx] / 3600.0
        color = COLORS[(class_id - 1) % len(COLORS)]
        name = CLASS_NAMES.get(class_id, f"Classe {class_id}")
        ax1.step(th, levels[idx], where="post", color=color, linewidth=1.1, alpha=0.85, label=name)
        ax2.step(th, oos[idx],    where="post", color=color, linewidth=1.3, alpha=0.85, label=name)

    ax1.set_ylabel("Livello scorta", fontsize=11)
    ax1.set_ylim(0)
    ax1.set_title("Inventario — tutte le classi (livello l(t))", fontsize=12, pad=8)
    ax1.grid(True, linewidth=0.4, alpha=0.5)
    ax1.legend(fontsize=9, ncol=5, framealpha=0.9, edgecolor="gray", loc="upper right")

    ax2.set_xlabel("Tempo (ore)", fontsize=11)
    ax2.set_ylabel("OOS cumulati", fontsize=11)
    ax2.set_ylim(0)
    ax2.set_title("Out-of-stock cumulati per classe", fontsize=11, pad=6)
    ax2.grid(True, linewidth=0.4, alpha=0.5)
    ax2.legend(fontsize=9, ncol=5, framealpha=0.9, edgecolor="gray", loc="upper left")

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
        print(f"  Classe {class_id}: {len(times)} estrazioni, {times[-1]/3600:.1f}h, "
              f"s={s_val}, S={S_val}, OOS={int(oos[-1])}")
        # SINGOLO
        out_single = os.path.join(args.out, f"inventory_class{class_id}.{args.fmt}")
        plot_class(class_id, s_val, S_val, times, levels, oos,
                   out_single, args.fmt, args.dpi, args.show, args.max_points)
        all_data.append((class_id, s_val, S_val, times, levels, oos))

    # COMBINATO
    if all_data:
        out_comb = os.path.join(args.out, f"inventory_combinato.{args.fmt}")
        plot_combined(all_data, out_comb, args.fmt, args.dpi, args.show, args.max_points)

    print("\nFatto. (singoli: inventory_class<k>.* | combinato: inventory_combinato.*)")


if __name__ == "__main__":
    main()
