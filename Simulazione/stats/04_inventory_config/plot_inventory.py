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

# NB: le statistiche di costo (holding €, shortage €) NON sono derivate qui: sono scritte per ogni
# classe nel .dat da InventorySystem.writeLevelTimeSeries (colonne holding_eur, shortage_eur) e qui
# vengono solo lette e plottate. Unica fonte = il codice di simulazione.


def add_halfhour_grid(ax, t_max_h):
    """Evidenzia le MEZZE ORE (fasce/revisioni ogni 30 min) con una linea verticale esplicita a
    ogni 0.5 h, come nei grafici dell'orizzonte finito. L'ora piena è tratteggiata e più marcata,
    la mezz'ora è punteggiata e più leggera."""
    from matplotlib.ticker import MultipleLocator
    n_half = int(round(t_max_h / 0.5))
    for i in range(0, n_half + 1):
        x = i * 0.5
        if i % 2 == 0:   # ora piena
            ax.axvline(x, color="gray", linewidth=0.8, linestyle="--", alpha=0.55, zorder=0)
        else:            # mezz'ora
            ax.axvline(x, color="gray", linewidth=0.6, linestyle=":", alpha=0.55, zorder=0)
    ax.xaxis.set_major_locator(MultipleLocator(1.0))
    ax.xaxis.set_minor_locator(MultipleLocator(0.5))
    ax.set_xlim(0, t_max_h)


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
    """Ritorna (s_val, S_val, times, levels, cum_oos, holding_eur, shortage_eur).
    Le colonne di costo (holding_eur, shortage_eur) sono scritte da InventorySystem.writeLevelTimeSeries;
    se il file è in formato vecchio (3 colonne) i costi tornano array vuoti (None)."""
    s_val = S_val = None
    times, levels, oos, hold, short = [], [], [], [], []
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
                    if len(parts) >= 5:
                        hold.append(float(parts[3]))
                        short.append(float(parts[4]))
                except ValueError:
                    continue
    holding  = np.array(hold)  if len(hold)  == len(times) else None
    shortage = np.array(short) if len(short) == len(times) else None
    return (s_val, S_val,
            np.array(times), np.array(levels, dtype=int), np.array(oos, dtype=int),
            holding, shortage)


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

    # Evidenzia le mezze ore (fasce/revisioni ogni 30 min), come nei grafici finiti
    for a in (ax1, ax2):
        add_halfhour_grid(a, th[-1])

    _save(fig, out_path, dpi, show)


# ── Vista COMBINATA (tutte le classi sovrapposte) ─────────────────────────────

def plot_combined(data, out_path, fmt, dpi, show, max_points):
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(13, 7), sharex=True,
                                   gridspec_kw={"height_ratios": [2, 1]})

    t_max_h = 0.0
    for (class_id, s_val, S_val, times, levels, oos, holding, shortage) in data:
        idx = decimate_idx(times, levels, oos, max_points)
        th = times[idx] / 3600.0
        t_max_h = max(t_max_h, th[-1])
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

    # Evidenzia le mezze ore (fasce/revisioni ogni 30 min), come nei grafici finiti
    for a in (ax1, ax2):
        add_halfhour_grid(a, t_max_h)

    _save(fig, out_path, dpi, show)


# ── Vista COSTI per classe (holding € e shortage €, cumulati) ─────────────────

def plot_class_cost(class_id, s_val, S_val, times, levels, oos, holding, shortage,
                    out_path, fmt, dpi, show, max_points):
    if holding is None or shortage is None:
        print(f"  [SKIP costi] classe {class_id}: il .dat non contiene le colonne holding_eur/"
              f"shortage_eur (rigenera con il codice aggiornato).")
        return
    hold, short = holding, shortage

    idx = decimate_idx(times, levels, oos, max_points)
    th = times[idx] / 3600.0
    color = COLORS[(class_id - 1) % len(COLORS)]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 6), sharex=True,
                                   gridspec_kw={"height_ratios": [1, 1]})

    ax1.step(th, hold[idx], where="post", color="#2ca02c", linewidth=1.4, label="Holding cumulato")
    ax1.set_ylabel("Holding [€]", fontsize=11); ax1.set_ylim(0)
    ax1.set_title(f"Costi inventario — {CLASS_NAMES.get(class_id, f'Classe {class_id}')}  —  "
                  f"holding={hold[-1]:.2f} €, shortage={short[-1]:.1f} €",
                  fontsize=12, pad=8)
    ax1.grid(True, linewidth=0.4, alpha=0.5)
    ax1.legend(fontsize=9, framealpha=0.9, edgecolor="gray", loc="upper left")

    ax2.step(th, short[idx], where="post", color="#d62728", linewidth=1.4, label="Shortage cumulato (OOS×p)")
    ax2.set_xlabel("Tempo (ore)", fontsize=11)
    ax2.set_ylabel("Shortage [€]", fontsize=11); ax2.set_ylim(0)
    ax2.grid(True, linewidth=0.4, alpha=0.5)
    ax2.legend(fontsize=9, framealpha=0.9, edgecolor="gray", loc="upper left")

    for a in (ax1, ax2):
        add_halfhour_grid(a, th[-1])
    _save(fig, out_path, dpi, show)


def plot_combined_cost(data, out_path, fmt, dpi, show, max_points):
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(13, 7), sharex=True,
                                   gridspec_kw={"height_ratios": [1, 1]})
    t_max_h = 0.0
    plotted = 0
    for (class_id, s_val, S_val, times, levels, oos, holding, shortage) in data:
        if holding is None or shortage is None:
            continue
        plotted += 1
        hold, short = holding, shortage
        idx = decimate_idx(times, levels, oos, max_points)
        th = times[idx] / 3600.0
        t_max_h = max(t_max_h, th[-1])
        color = COLORS[(class_id - 1) % len(COLORS)]
        name = CLASS_NAMES.get(class_id, f"Classe {class_id}")
        ax1.step(th, hold[idx],  where="post", color=color, linewidth=1.1, alpha=0.85, label=name)
        ax2.step(th, short[idx], where="post", color=color, linewidth=1.3, alpha=0.85, label=name)

    ax1.set_ylabel("Holding [€]", fontsize=11); ax1.set_ylim(0)
    ax1.set_title("Costo di holding cumulato per classe (h · L̄ giornaliero)", fontsize=12, pad=8)
    ax1.grid(True, linewidth=0.4, alpha=0.5)
    ax1.legend(fontsize=9, ncol=5, framealpha=0.9, edgecolor="gray", loc="upper left")

    ax2.set_xlabel("Tempo (ore)", fontsize=11)
    ax2.set_ylabel("Shortage [€]", fontsize=11); ax2.set_ylim(0)
    ax2.set_title("Costo di shortage cumulato per classe (OOS × p, lost-sales)", fontsize=11, pad=6)
    ax2.grid(True, linewidth=0.4, alpha=0.5)
    ax2.legend(fontsize=9, ncol=5, framealpha=0.9, edgecolor="gray", loc="upper left")

    if plotted == 0:
        print("  [SKIP costi combinato] nessuna classe ha le colonne di costo nel .dat "
              "(rigenera con il codice aggiornato).")
        plt.close(fig)
        return
    for a in (ax1, ax2):
        add_halfhour_grid(a, t_max_h)
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
        s_val, S_val, times, levels, oos, holding, shortage = load_level_dat(fpath)
        if len(times) == 0:
            print(f"  [WARN] vuoto: {fpath}")
            continue
        print(f"  Classe {class_id}: {len(times)} estrazioni, {times[-1]/3600:.1f}h, "
              f"s={s_val}, S={S_val}, OOS={int(oos[-1])}")
        # SINGOLO: livello + OOS
        out_single = os.path.join(args.out, f"inventory_class{class_id}.{args.fmt}")
        plot_class(class_id, s_val, S_val, times, levels, oos,
                   out_single, args.fmt, args.dpi, args.show, args.max_points)
        # SINGOLO: costi (holding € + shortage €) — letti dal .dat
        out_cost = os.path.join(args.out, f"inventory_class{class_id}_costi.{args.fmt}")
        plot_class_cost(class_id, s_val, S_val, times, levels, oos, holding, shortage,
                        out_cost, args.fmt, args.dpi, args.show, args.max_points)
        all_data.append((class_id, s_val, S_val, times, levels, oos, holding, shortage))

    # COMBINATO: livello + OOS
    if all_data:
        out_comb = os.path.join(args.out, f"inventory_combinato.{args.fmt}")
        plot_combined(all_data, out_comb, args.fmt, args.dpi, args.show, args.max_points)
        # COMBINATO: costi
        out_comb_cost = os.path.join(args.out, f"inventory_costi_combinato.{args.fmt}")
        plot_combined_cost(all_data, out_comb_cost, args.fmt, args.dpi, args.show, args.max_points)

    print("\nFatto. (livello: inventory_class<k>.* / inventory_combinato.* | "
          "costi: inventory_class<k>_costi.* / inventory_costi_combinato.*)")


if __name__ == "__main__":
    main()
