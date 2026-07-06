#!/usr/bin/env python3
"""
Grafici dell'attività 02 — calibrazione della batch size (metodo batch means, slide lect26):
evidenza a supporto della configurazione scelta (k=64, b=1024, gap=0).

FIGURA 1 (chatfield_r1_vs_b.png): |r1| dei batch means in funzione di b (scala log2),
per le serie INCLUSE nel criterio (GLOB, Casse, Online, B.Due) su piu' seed, con:
  - linea rossa  = soglia di Chatfield 2/sqrt(k) = 0.25;
  - linea arancio = margine di accettazione 0.8*soglia = 0.20;
  - stella sulla b ACCETTATA dalla ricerca canonica (chatfield_search.dat).
Fonti: dat/chatfield_landscape.dat (sweep diagnostico, tutte le b) +
       dat/chatfield_search.dat (probe della ricerca canonica).

FIGURA 2 (acf_batchmeans.png): correlogramma r_j (j=1..16) dei batch means alla b
accettata, per serie: tutti i lag devono cadere nella banda di rumore +-2/sqrt(k)
(Chatfield vincola il lag-1; i lag>1 sono mostrati come conferma che il residuo
e' dominato dal lag-1, CLAUDE.md §17.4).
Fonte: dat/acf_batchmeans_b<b>.dat.

FIGURA 3 (batchmeans_series.png): le serie dei batch means del response (GLOB +
serie incluse) alla b accettata — a occhio devono sembrare rumore bianco attorno
alla media (nessun trend/derive = warm-up sufficiente, nessuna onda = batch
~indipendenti). Fonte: dat/batchmeans_b<b>.dat.

Uso:
    python plot_batch_calibration.py            # legge ./dat, scrive ./grafici
    python plot_batch_calibration.py --dat DIR --out-dir DIR
Convenzione progetto: niente bande di confidenza nei grafici (CLAUDE.md §20.6).
"""
import os, sys, glob, argparse
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

# Ordine colonne nei .dat (header "# b  seed  GLOB Casse Online Disp B.Uno B.Due Mag Pag  max_incl")
SERIES   = ["GLOB", "Casse", "Online", "Disp", "B.Uno", "B.Due", "Mag", "Pag"]
INCLUDED = ["GLOB", "Casse", "Online", "B.Due"]        # serie del criterio (come nel Java)
COLORS   = {"GLOB": "#2c3e50", "Casse": "#4472C4", "Online": "#ED7D31", "B.Due": "#70AD47"}


def read_trace(path):
    """chatfield_search/landscape.dat -> {(b, seed): {serie: |r1|}}"""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) < 2 + len(SERIES):
                continue
            b, seed = int(p[0]), int(p[1])
            out[(b, seed)] = {s: float(p[2 + i]) for i, s in enumerate(SERIES)}
    return out


def read_matrix(path):
    """acf/batchmeans .dat -> (x[], {serie: y[]})"""
    xs, ys = [], defaultdict(list)
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            xs.append(float(p[0]))
            for i, s in enumerate(SERIES):
                ys[s].append(float(p[1 + i]))
    return xs, ys


def accepted_b(dat_dir):
    """b accettata = quella dei file acf_batchmeans_b<b>.dat scritti dalla ricerca canonica."""
    hits = sorted(glob.glob(os.path.join(dat_dir, "acf_batchmeans_b*.dat")))
    if not hits:
        sys.exit(f"Nessun acf_batchmeans_b*.dat in {dat_dir}: eseguire prima BatchCalibration")
    return int(os.path.basename(hits[-1]).replace("acf_batchmeans_b", "").replace(".dat", ""))


def fig_r1_vs_b(plt, trace, b_acc, threshold, margin, out):
    fig, ax = plt.subplots(figsize=(8.5, 5.2))
    bs = sorted({b for b, _ in trace})
    for s in INCLUDED:
        # media sui seed (linea) + singoli seed (puntini) per mostrare la variabilita'
        mean_y = [sum(trace[(b, sd)][s] for b2, sd in trace if b2 == b) /
                  max(1, sum(1 for b2, _ in trace if b2 == b)) for b in bs]
        ax.plot(bs, mean_y, marker="o", ms=5, lw=1.8, color=COLORS[s], label=s)
        pts = [(b, trace[(b, sd)][s]) for b, sd in trace]
        ax.scatter([p[0] for p in pts], [p[1] for p in pts], s=14, color=COLORS[s], alpha=0.35)

    ax.axhline(threshold, color="#c0392b", lw=1.6, ls="--",
               label=f"soglia Chatfield 2/√k = {threshold:.2f}")
    ax.axhline(margin, color="#e67e22", lw=1.3, ls=":",
               label=f"margine accettazione = {margin:.2f}")

    # stella sulla b accettata, all'altezza del max fra le serie incluse (media seed)
    y_star = max(sum(trace[(b, sd)][s] for b2, sd in trace if b2 == b_acc for s in [ser])
                 / max(1, sum(1 for b2, _ in trace if b2 == b_acc))
                 for ser in INCLUDED
                 for b, sd in [(b_acc, 0)])
    ax.plot([b_acc], [y_star], marker="*", ms=22, mfc="gold", mec="k", mew=1.2, ls="none",
            zorder=6, label=f"b scelta = {b_acc} (k=64, gap=0)")

    ax.set_xscale("log", base=2)
    ax.set_xticks(bs)
    ax.set_xticklabels([str(b) for b in bs])
    ax.set_xlabel("batch size b (log₂)")
    ax.set_ylabel("|r₁| dei batch means")
    ax.set_title("Calibrazione batch size — |r₁| vs b (k=64, gap=0, λ=0.0190975)\n"
                 "serie incluse nel criterio; punti = singoli seed, linea = media",
                 fontsize=11, fontweight="bold")
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8, loc="upper right")
    ax.set_ylim(bottom=0)
    fig.tight_layout()
    fig.savefig(out, dpi=130)
    print(f"Scritto: {out}")


def fig_acf(plt, dat_dir, b_acc, threshold, out):
    lags, acfs = read_matrix(os.path.join(dat_dir, f"acf_batchmeans_b{b_acc}.dat"))
    fig, axes = plt.subplots(2, 2, figsize=(9.5, 6.4), sharex=True, sharey=True)
    for ax, s in zip(axes.flatten(), INCLUDED):
        ax.bar(lags, acfs[s], width=0.55, color=COLORS[s])
        ax.axhline(threshold, color="#c0392b", lw=1.2, ls="--")
        ax.axhline(-threshold, color="#c0392b", lw=1.2, ls="--")
        ax.axhline(0, color="k", lw=0.8)
        ax.set_title(f"{s}   (|r₁| = {abs(acfs[s][0]):.3f})", fontsize=10)
        ax.set_ylim(-0.45, 0.45)
        ax.grid(alpha=0.2)
    for ax in axes[1]:
        ax.set_xlabel("lag j")
    for ax in axes[:, 0]:
        ax.set_ylabel("r_j")
    fig.suptitle(f"Correlogramma dei batch means alla b scelta = {b_acc} (k=64)\n"
                 f"banda di rumore ±2/√k = ±{threshold:.2f}: tutti i lag dentro ⇒ batch ≈ indipendenti",
                 fontsize=11, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(out, dpi=130)
    print(f"Scritto: {out}")


def fig_series(plt, dat_dir, b_acc, out):
    idx, means = read_matrix(os.path.join(dat_dir, f"batchmeans_b{b_acc}.dat"))
    fig, axes = plt.subplots(2, 2, figsize=(10, 6.2))
    for ax, s in zip(axes.flatten(), INCLUDED):
        y = means[s]
        m = sum(y) / len(y)
        ax.plot(idx, y, lw=1.1, color=COLORS[s])
        ax.axhline(m, color="k", lw=0.9, ls="--", label=f"media = {m:.1f} s")
        ax.set_title(s, fontsize=10)
        ax.set_xlabel("batch")
        ax.set_ylabel("response medio [s]")
        ax.grid(alpha=0.2)
        ax.legend(fontsize=7.5)
    fig.suptitle(f"Batch means del response alla b scelta = {b_acc} (k=64, seed primario)\n"
                 "atteso: fluttuazione ~bianca attorno alla media (no trend ⇒ warm-up ok)",
                 fontsize=11, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(out, dpi=130)
    print(f"Scritto: {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dat", default=os.path.join(HERE, "dat"))
    ap.add_argument("--out-dir", default=os.path.join(HERE, "grafici"))
    ap.add_argument("--k", type=int, default=64, help="numero di batch (soglia = 2/sqrt(k))")
    ap.add_argument("--margin", type=float, default=0.8, help="frazione della soglia per accettare")
    args = ap.parse_args()

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("matplotlib non installato: pip install matplotlib")

    threshold = 2.0 / (args.k ** 0.5)
    margin = args.margin * threshold
    b_acc = accepted_b(args.dat)

    # traccia: sweep diagnostico (tutte le b) + probe della ricerca canonica
    trace = read_trace(os.path.join(args.dat, "chatfield_landscape.dat"))
    trace.update(read_trace(os.path.join(args.dat, "chatfield_search.dat")))
    if not trace:
        sys.exit(f"Nessuna traccia in {args.dat} (chatfield_search/landscape.dat)")

    os.makedirs(args.out_dir, exist_ok=True)
    fig_r1_vs_b(plt, trace, b_acc, threshold, margin,
                os.path.join(args.out_dir, "chatfield_r1_vs_b.png"))
    fig_acf(plt, args.dat, b_acc, threshold,
            os.path.join(args.out_dir, "acf_batchmeans.png"))
    fig_series(plt, args.dat, b_acc,
               os.path.join(args.out_dir, "batchmeans_series.png"))


if __name__ == "__main__":
    main()
