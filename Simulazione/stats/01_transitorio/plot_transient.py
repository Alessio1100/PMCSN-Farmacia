"""
plot_transient.py
-----------------
Legge i file .dat prodotti da TransientAnalysis.java e genera grafici del transitorio.
Gestisce separatore decimale virgola (locale italiano Java su Windows) o punto.

Legge i .dat dalla sottocartella 'dat/' e salva i grafici in 'grafici/' (entrambe accanto a
questo script, in stats/01_transitorio/). Eseguibile da qualunque directory.

Uso:
    python stats/01_transitorio/plot_transient.py
    python plot_transient.py --dir ./dat --out ./grafici --fmt pdf --show
"""

import argparse
import os
import sys
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

# Cartelle di default ANCORATE alla posizione dello script (non al cwd):
#   dat/      -> file .dat prodotti da TransientAnalysis
#   grafici/  -> immagini generate
SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DAT = os.path.join(SCRIPT_DIR, "dat")
DEFAULT_OUT = os.path.join(SCRIPT_DIR, "grafici")

FILES = {
    "transient_responseTime.dat": {
        "title":  "Tempo di risposta - Sistema",
        "ylabel": "E(Ts)",
        "ylim":   None,
    },
    "transient_utilCasse.dat": {
        "title":  "Utilizzazione media - Casse fisiche",
        "ylabel": "Utilizzazione",
        "ylim":   (0, 1),
    },
    "transient_numInSystem.dat": {
        "title":  "Numero medio job nel sistema - E[N]",
        "ylabel": "E[N]",
        "ylim":   None,
    },
}

COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#8c564b",
          "#e377c2", "#7f7f7f", "#bcbd22", "#17becf", "#ff7f0e"]

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--dir",  default=DEFAULT_DAT, help="cartella con i .dat (default: ./dat)")
    p.add_argument("--out",  default=DEFAULT_OUT, help="cartella output grafici (default: ./grafici)")
    p.add_argument("--fmt",  default="png")
    p.add_argument("--dpi",  default=150, type=int)
    p.add_argument("--show", action="store_true")
    return p.parse_args()

def load_dat(path):
    """
    Legge il file .dat tab-separated con header che inizia con '#'.
    Gestisce separatore decimale virgola (Java locale italiano) o punto.
    """
    with open(path, "r", encoding="utf-8") as f:
        header_line = f.readline().lstrip("# ").strip()
    col_names = header_line.split("\t")

    # Legge tutto come stringhe per gestire la virgola decimale
    df = pd.read_csv(
        path,
        sep="\t",
        comment="#",
        header=None,
        names=col_names,
        dtype=str,
    )

    # Normalizza separatore decimale e converte a float
    for col in df.columns:
        df[col] = (
            df[col]
            .str.strip()
            .str.replace(",", ".", regex=False)
            .astype(float)
        )

    return df

def regime_of(df):
    """Media a regime = media dell'ultimo 30% dei punti su TUTTE le repliche (dati completi 40k)."""
    replica_cols = [c for c in df.columns if c != "completamenti"]
    tails = []
    for col in replica_cols:
        y = df[col].values
        tails.extend(y[int(len(y) * 0.70):].tolist())
    return float(np.mean(tails))


def plot_metric(df, meta, out_path, fmt, dpi, show, xmax=None, regime_mean=None, mark_10k=False):
    """Grafico del transitorio. xmax = limite asse X (job) per la vista ristretta (es. 10k).
    regime_mean = linea di regime calcolata sui dati COMPLETI (così nella vista 10k si vede se la
    curva ha già raggiunto il regime vero). mark_10k = traccia una verticale a 10k (vista 40k)."""
    x_full       = df["completamenti"].values
    replica_cols = [c for c in df.columns if c != "completamenti"]

    mask = x_full <= xmax if xmax is not None else np.ones_like(x_full, dtype=bool)
    x    = x_full[mask]

    if regime_mean is None:
        regime_mean = regime_of(df)

    fig, ax = plt.subplots(figsize=(10, 6))

    # Repliche in secondo piano (più trasparenti): fanno da "banda", risalta la media.
    ys = []
    for i, col in enumerate(replica_cols):
        y     = df[col].values[mask]
        ys.append(y)
        color = COLORS[i % len(COLORS)]
        ax.plot(x, y, color=color, linewidth=1.0, alpha=0.6, label=col.replace("seed_", ""))

    # Media d'ensemble: media TRA le repliche a ogni istante (curva, non retta) → mostra come si
    # comporta e si stabilizza la media al crescere dei job.
    ens_mean = np.mean(np.vstack(ys), axis=0)
    ax.plot(x, ens_mean, color="black", linewidth=1.5, alpha=0.95, zorder=6,
            label=f"Media d'ensemble ({len(ys)} repliche)")

    # Regime (asintoto stimato dai dati completi 40k) come riferimento leggero.
    ax.axhline(regime_mean, color="black", linewidth=1.0, linestyle=":", alpha=0.55,
               label=f"regime 40k ≈ {regime_mean:.1f}", zorder=5)

    if mark_10k and (x_full.max() > 10000):
        ax.axvline(10000, color="gray", linewidth=1.2, linestyle="--", alpha=0.8,
                   label="10k (vecchio cutoff)", zorder=4)

    ax.set_xlabel("Numero di job", fontsize=12)
    ax.set_ylabel(meta["ylabel"], fontsize=12)
    span = f"0–{int(x.max()/1000)}k" if len(x) else "0"
    ax.set_title(f"{meta['title']}  (vista {span} job)", fontsize=13, pad=10)

    if meta["ylim"] is not None:
        ax.set_ylim(meta["ylim"])
    if len(x):
        ax.set_xlim(x.min(), x.max())

    ax.xaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.grid(True, linewidth=0.5, alpha=0.6)
    ax.legend(title="Initial seed", fontsize=9, title_fontsize=9,
              loc="upper right", framealpha=0.9, edgecolor="gray")

    fig.tight_layout()
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    fig.savefig(out_path, dpi=dpi, bbox_inches="tight")
    print(f"  Salvato: {out_path}")

    if show:
        plt.show()
    plt.close(fig)

def print_summary(dat_dir):
    print("\n-- Riepilogo media a regime --------------------------------------")
    print(f"  {'File':<35}  {'Media a regime':>16}")
    print("  " + "-" * 55)
    for filename in FILES:
        path = os.path.join(dat_dir, filename)
        if not os.path.exists(path):
            continue
        df    = load_dat(path)
        rcols = [c for c in df.columns if c != "completamenti"]
        tails = []
        for col in rcols:
            y = df[col].values
            tails.extend(y[int(len(y) * 0.70):].tolist())
        print(f"  {filename:<35}  {float(np.mean(tails)):>16.4f}")
    print()

def main():
    args = parse_args()
    os.makedirs(args.out, exist_ok=True)

    found = False
    for filename, meta in FILES.items():
        in_path  = os.path.join(args.dir, filename)
        stem     = os.path.splitext(filename)[0]
        out_path = os.path.join(args.out, f"{stem}.{args.fmt}")

        if not os.path.exists(in_path):
            print(f"  [SKIP] Non trovato: {in_path}")
            continue

        found = True
        df    = load_dat(in_path)
        if df.empty:
            print(f"  [WARN] File vuoto: {in_path}")
            continue

        rcols = [c for c in df.columns if c != "completamenti"]
        print(f"  {filename}  ({len(df)} punti, {len(rcols)} repliche)...")

        # Regime dai dati COMPLETI (40k) → stessa linea in entrambe le viste.
        regime = regime_of(df)
        # Due viste per ogni statistica: ristretta a 10k e completa (40k).
        for suffix, xmax, mark in (("10k", 10000, False), ("40k", None, True)):
            out_v = os.path.join(args.out, f"{stem}_{suffix}.{args.fmt}")
            plot_metric(df, meta, out_v, args.fmt, args.dpi, args.show,
                        xmax=xmax, regime_mean=regime, mark_10k=mark)

    if not found:
        print(f"\n[ERRORE] Nessun file .dat trovato in '{args.dir}'.")
        sys.exit(1)

    print_summary(args.dir)

if __name__ == "__main__":
    main()