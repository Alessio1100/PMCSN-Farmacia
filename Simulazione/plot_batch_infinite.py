"""
plot_batch_infinite.py
----------------------
Legge i file stats/infinite_*.dat prodotti da Simulation.writeBatchDatFiles()
e genera, per ogni file, un grafico con:
  - valori per-batch (punti blu)
  - media cumulativa (linea rossa)
  - banda CI al 95% cumulativa (area grigia)
  - media finale (linea nera tratteggiata)

Uso:
    python plot_batch_infinite.py
    python plot_batch_infinite.py --dir ./stats --out ./stats/plots --fmt pdf --show
"""

import argparse
import os
import sys
import glob
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import scipy.stats as stats

METRIC_LABELS = {
    "responseTime": "Tempo di risposta E[T] (s)",
    "utilization":  "Utilizzazione ρ",
    "avgQueue":     "Numero medio in coda E[Nq]",
    "lossProb":     "Probabilità di perdita",
}

CENTER_LABELS = {
    "Casse":        "Casse fisiche",
    "CassaOnline":  "Cassa Online",
    "Dispatcher":   "Dispatcher",
    "BraccioUno":   "Braccio Uno",
    "BraccioDue":   "Braccio Due",
    "Magazziniere": "Magazziniere",
    "system":       "Sistema",
}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--dir",  default="stats/infinite")
    p.add_argument("--out",  default="stats/infinite")
    p.add_argument("--fmt",  default="png")
    p.add_argument("--dpi",  default=150, type=int)
    p.add_argument("--no-show", dest="show", action="store_false", default=True)
    return p.parse_args()


def load_dat(path):
    df = pd.read_csv(path, sep="\t", comment="#", header=None, names=["batch", "value"])
    df["value"] = df["value"].astype(float)
    return df


def running_ci(values, confidence=0.95):
    """Returns (running_mean, lower_ci, upper_ci) arrays."""
    n = len(values)
    rm   = np.zeros(n)
    low  = np.zeros(n)
    high = np.zeros(n)
    for i in range(n):
        sub = values[:i+1]
        m   = np.mean(sub)
        rm[i] = m
        if i < 2:
            low[i] = high[i] = m
            continue
        se  = np.std(sub, ddof=1) / np.sqrt(i+1)
        t   = stats.t.ppf((1 + confidence) / 2, df=i)
        low[i]  = m - t * se
        high[i] = m + t * se
    return rm, low, high


def title_from_stem(stem):
    """Converts 'infinite_Casse_responseTime' into a human-readable title."""
    parts = stem.replace("infinite_", "").split("_", 1)
    center = CENTER_LABELS.get(parts[0], parts[0]) if len(parts) > 0 else stem
    metric = METRIC_LABELS.get(parts[1], parts[1]) if len(parts) > 1 else ""
    return f"{center} — {metric}"


def ylabel_from_stem(stem):
    for key, label in METRIC_LABELS.items():
        if key in stem:
            return label
    return "Valore"


def plot_batch(df, stem, out_path, fmt, dpi, show):
    values = df["value"].values
    x      = df["batch"].values
    rm, lo, hi = running_ci(values)
    final_mean = float(np.mean(values))

    fig, ax = plt.subplots(figsize=(10, 5))

    ax.fill_between(x, lo, hi, color="#aec7e8", alpha=0.5, label="IC 95% cumulativo")
    ax.plot(x, values, "o-", color="#1f77b4", markersize=4, linewidth=1.2, alpha=0.85, label="Valore per batch")
    ax.plot(x, rm,     "-", color="#d62728", linewidth=1.8, label="Media cumulativa")
    ax.axhline(final_mean, color="black", linewidth=1.2, linestyle="--",
               label=f"Media finale = {final_mean:.4f}")

    ax.set_xlabel("Numero di batch", fontsize=12)
    ax.set_ylabel(ylabel_from_stem(stem), fontsize=12)
    ax.set_title(title_from_stem(stem), fontsize=13, pad=10)
    ax.set_xlim(x[0] - 0.5, x[-1] + 0.5)
    ax.grid(True, linewidth=0.5, alpha=0.5)
    ax.legend(fontsize=9, framealpha=0.9, edgecolor="gray")

    fig.tight_layout()
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    fig.savefig(out_path, dpi=dpi, bbox_inches="tight")
    print(f"  Salvato: {out_path}")

    if show:
        plt.show()
    plt.close(fig)


def main():
    args = parse_args()
    pattern = os.path.join(args.dir, "infinite_*.dat")
    files   = sorted(glob.glob(pattern))

    if not files:
        print(f"[ERRORE] Nessun file infinite_*.dat trovato in '{args.dir}'.")
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)

    for fpath in files:
        stem     = os.path.splitext(os.path.basename(fpath))[0]
        out_path = os.path.join(args.out, f"{stem}.{args.fmt}")

        df = load_dat(fpath)
        if df.empty:
            print(f"  [WARN] File vuoto: {fpath}")
            continue

        print(f"  {os.path.basename(fpath)}  ({len(df)} batch)...")
        plot_batch(df, stem, out_path, args.fmt, args.dpi, args.show)

    print("\nFatto.")


if __name__ == "__main__":
    main()
