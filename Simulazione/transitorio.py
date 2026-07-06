"""
plot_transient.py
-----------------
Legge i file .dat prodotti da TransientAnalysis.java e genera grafici del transitorio.
Gestisce separatore decimale virgola (locale italiano Java su Windows) o punto.

Uso:
    python plot_transient.py
    python plot_transient.py --dir ./stats --out ./stats --fmt pdf --show
"""

import argparse
import os
import sys
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

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
    p.add_argument("--dir",  default="stats")
    p.add_argument("--out",  default="stats")
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

def plot_metric(df, meta, out_path, fmt, dpi, show):
    x            = df["completamenti"].values
    replica_cols = [c for c in df.columns if c != "completamenti"]

    fig, ax = plt.subplots(figsize=(10, 6))

    all_tails = []
    for i, col in enumerate(replica_cols):
        y     = df[col].values
        color = COLORS[i % len(COLORS)]
        label = col.replace("seed_", "")
        ax.plot(x, y, color=color, linewidth=1.2, alpha=0.9, label=label)
        tail_start = int(len(y) * 0.70)
        all_tails.extend(y[tail_start:].tolist())

    regime_mean = float(np.mean(all_tails))
    ax.axhline(
        regime_mean,
        color="black", linewidth=1.5, linestyle="-",
        label=f"y = media = {regime_mean:.2f}",
        zorder=5,
    )

    ax.set_xlabel("Numero di job", fontsize=12)
    ax.set_ylabel(meta["ylabel"], fontsize=12)
    ax.set_title(meta["title"], fontsize=13, pad=10)

    if meta["ylim"] is not None:
        ax.set_ylim(meta["ylim"])

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
    print("\n── Riepilogo media a regime ──────────────────────────────────────")
    print(f"  {'File':<35}  {'Media a regime':>16}")
    print("  " + "─" * 55)
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
        plot_metric(df, meta, out_path, args.fmt, args.dpi, args.show)

    if not found:
        print(f"\n[ERRORE] Nessun file .dat trovato in '{args.dir}'.")
        sys.exit(1)

    print_summary(args.dir)

if __name__ == "__main__":
    main()