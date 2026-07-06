"""
plot_finite_horizon.py
----------------------
Legge i file stats/{centro}_{metrica}.dat prodotti da FiniteHorizonSimulation.java
(formato: 3 colonne tab-separated: mean, lower_ci, upper_ci — 96 righe per file)
e genera un grafico per ogni file con:
  - curva della media
  - banda IC al 95% (area colorata)
  - linee verticali tratteggiate ai cambi di fascia oraria (ogni 30 min)

Uso:
    python plot_finite_horizon.py
    python plot_finite_horizon.py --dir ./stats --out ./stats --fmt pdf --show
"""

import argparse
import os
import sys
import glob
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

NUM_SAMPLING   = 96
SIM_DURATION_MIN = 720    # 12 ore
SLOT_DURATION_MIN = 30    # ogni 30 min cambia fascia oraria
SAMPLES_PER_MIN = NUM_SAMPLING / SIM_DURATION_MIN  # = 0.1333

TIME_MIN = np.linspace(SIM_DURATION_MIN / NUM_SAMPLING, SIM_DURATION_MIN, NUM_SAMPLING)

SLOT_CHANGE_TIMES = list(range(SLOT_DURATION_MIN, SIM_DURATION_MIN, SLOT_DURATION_MIN))

CENTER_LABELS = {
    "Casse Farmacia": "Farmacisti",
    "Cassa Online":   "Cassa Online",
    "Dispatcher":     "Dispatcher",
    "Braccio Uno":      "Braccio Uno",
    "Braccio Uno HIGH": "Braccio Uno — Carico",
    "Braccio Uno LOW":  "Braccio Uno — Prelievo",
    "Braccio Due":    "Braccio Due",
    "Casse Pagamento":"Casse Pagamento",
    "Magazziniere":   "Magazziniere",
    "system":         "Sistema (end-to-end)",
}

METRIC_LABELS = {
    "Response":           "Tempo di risposta E[T] (s)",
    "Wait":               "Tempo di attesa in coda E[W] (s)",
    "Node":               "Numero medio nel nodo E[N]",
    "Queue":              "Numero medio in coda E[Nq]",
    "Utilization":        "Utilizzazione ρ",
    "lossProbability":    "Probabilità di perdita / OOS",
    "avgInterarrivals":   "Tempo medio inter-arrivi (s)",
    "arrivalsPerWindow":  "Arrivi per finestra",
    "completionsPerWindow":"Completamenti per finestra",
    "abandonPerWindow":   "Abbandoni per finestra",
}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--dir",  default=os.path.join(SCRIPT_DIR, "dat"))
    p.add_argument("--out",  default=os.path.join(SCRIPT_DIR, "grafici"))
    p.add_argument("--fmt",  default="png")
    p.add_argument("--prefix", default="finite_",
                   help="Prefisso dei file di output (default finite_; per la baseline usa baseline_)")
    p.add_argument("--dpi",  default=150, type=int)
    p.add_argument("--show", action="store_true", default=False,
                   help="Mostra a schermo oltre a salvare (default: salva soltanto)")
    return p.parse_args()


def load_dat(path):
    """
    Legge un dat 3-colonne: mean, lower_ci, upper_ci.
    Gestisce sia i nuovi file 3-colonne che i vecchi file 1-colonna (senza CI).
    """
    with open(path, "r", encoding="utf-8") as f:
        lines = [l for l in f if not l.startswith("#") and l.strip()]

    rows = [l.strip().split("\t") for l in lines]
    if not rows:
        return None

    if len(rows[0]) >= 3:
        means  = [float(r[0].replace(",", ".")) for r in rows]
        lowers = [float(r[1].replace(",", ".")) for r in rows]
        uppers = [float(r[2].replace(",", ".")) for r in rows]
        return np.array(means), np.array(lowers), np.array(uppers)
    else:
        # old single-column format — no CI available
        means = [float(r[0].replace(",", ".")) for r in rows]
        arr   = np.array(means)
        return arr, arr, arr


# Rinomina del CENTRO nel NOME FILE di output (i .dat restano invariati: sono lo
# standard di progetto). Le due sotto-code del Braccio Uno prendono nomi parlanti anche
# nei PNG generati (HIGH=carico rifornimento, LOW=prelievo cliente).
FILE_CENTER_RENAME = {
    "Braccio Uno HIGH": "Braccio Uno Carico",
    "Braccio Uno LOW":  "Braccio Uno Prelievo",
}


def out_stem(stem):
    """Applica FILE_CENTER_RENAME al centro nel nome file (parte prima dell'ultimo '_')."""
    if "_" not in stem:
        return stem
    idx = stem.rfind("_")
    center, metric = stem[:idx], stem[idx + 1:]
    return f"{FILE_CENTER_RENAME.get(center, center)}_{metric}"


def stem_to_meta(stem):
    """Decompone 'Casse Farmacia_Response' in (center_label, metric_label, title)."""
    if "_" not in stem:
        return stem, stem, stem
    idx    = stem.rfind("_")
    center = stem[:idx]
    metric = stem[idx+1:]
    clabel = CENTER_LABELS.get(center, center)
    mlabel = METRIC_LABELS.get(metric, metric)
    title  = f"{clabel} — {mlabel}"
    return clabel, mlabel, title


def plot_finite(means, lowers, uppers, stem, out_path, fmt, dpi, show):
    x = TIME_MIN[:len(means)]

    _, ylabel, title = stem_to_meta(stem)

    fig, ax = plt.subplots(figsize=(12, 5))

    ax.fill_between(x, lowers, uppers, color="#aec7e8", alpha=0.4, label="IC 95%")
    ax.plot(x, means, color="#1f77b4", linewidth=1.8, label="Media (128 repliche)")

    for sc_time in SLOT_CHANGE_TIMES:
        ax.axvline(sc_time, color="gray", linewidth=0.7, linestyle="--", alpha=0.6)

    # Etichetta fasce orarie (solo alcune per non affollare)
    start_hour = 8
    for i, sc_time in enumerate(SLOT_CHANGE_TIMES):
        if i % 2 == 0:
            hour = start_hour + (sc_time // 60)
            minute = sc_time % 60
            ax.text(sc_time, ax.get_ylim()[1] * 0.97, f"{hour:02d}:{minute:02d}",
                    fontsize=6, color="gray", ha="center", va="top")

    ax.set_xlabel("Tempo di simulazione (min)", fontsize=12)
    ax.set_ylabel(ylabel, fontsize=12)
    ax.set_title(title, fontsize=13, pad=10)
    ax.set_xlim(x[0], x[-1])
    ax.grid(True, linewidth=0.4, alpha=0.5)

    ci_patch = mpatches.Patch(color="#aec7e8", alpha=0.7, label="IC 95%")
    ax.legend(handles=[
        plt.Line2D([0], [0], color="#1f77b4", linewidth=1.8, label="Media (128 repliche)"),
        ci_patch,
    ], fontsize=9, framealpha=0.9, edgecolor="gray")

    fig.tight_layout()
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    fig.savefig(out_path, dpi=dpi, bbox_inches="tight")
    print(f"  Salvato: {out_path}")

    if show:
        plt.show()
    plt.close(fig)


def main():
    args = parse_args()

    # Esclude transient_, infinite_ e inventory_ (quest'ultimo ha formato time/level/cum_oos,
    # gestito da plot_inventory.py — non è una serie mean/CI per fascia) e i file di servizio
    # chiave/valore (summary.dat) o tabellari (best_*, results) prodotti dalle altre attività.
    pattern = os.path.join(args.dir, "*.dat")
    files   = sorted(glob.glob(pattern))
    files   = [f for f in files
               if not os.path.basename(f).startswith("transient_")
               and not os.path.basename(f).startswith("infinite_")
               and not os.path.basename(f).startswith("inventory_")
               and not os.path.basename(f).startswith("best_")
               and os.path.basename(f) not in ("summary.dat", "results.dat")]

    if not files:
        print(f"[ERRORE] Nessun file .dat di orizzonte finito trovato in '{args.dir}'.")
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)

    for fpath in files:
        stem     = os.path.splitext(os.path.basename(fpath))[0]
        out_path = os.path.join(args.out, f"{args.prefix}{out_stem(stem)}.{args.fmt}")

        result = load_dat(fpath)
        if result is None:
            print(f"  [WARN] File vuoto: {fpath}")
            continue
        means, lowers, uppers = result

        print(f"  {os.path.basename(fpath)}  ({len(means)} campioni)...")
        plot_finite(means, lowers, uppers, stem, out_path, args.fmt, args.dpi, args.show)

    print("\nFatto.")


if __name__ == "__main__":
    main()
