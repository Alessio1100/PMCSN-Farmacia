#!/usr/bin/env python3
"""
Confronta la versione OVERFLOW (stato-dipendente, attuale) con lo scheduling
ASTRATTO a probabilita' fissa p_b1 (frammenti cliente -> Braccio Uno), per trovare
il p_b1 (multipli di 5%) che rende le statistiche piu' simili alla versione corrente.

Uso:
    python compare_routing.py            # reps=64, p_b1 in {0,5,...,25}%
    REPS=128 python compare_routing.py

Richiede: build_cmp/ gia' compilato (javac -d build_cmp @sources.txt).
"""
import os, subprocess, sys

BASE = os.path.dirname(os.path.abspath(__file__))
CP   = os.path.join(BASE, "build_cmp")
STATS = os.path.join(BASE, "stats", "finite")
REPS = os.environ.get("REPS", "64")
MAIN = "it.farmacia.control.FiniteHorizonSimulation"

# p_b1 da testare (multipli di 5%, sotto il limite di stabilita' ~28%)
PB1_GRID = [0.00, 0.05, 0.10, 0.15, 0.20, 0.25]

# Metriche su cui misurare la similarita' (file in stats/finite, colonna 1 = media)
METRICS = [
    ("system_avgWait.dat",        "Sys Response"),
    ("system_avgNode.dat",        "Sys E[N]"),
    ("Braccio Uno_utilization.dat","B1 util"),
    ("Braccio Uno_avgWait.dat",    "B1 Resp"),
    ("Braccio Due_utilization.dat","B2 util"),
    ("Braccio Due_avgWait.dat",    "B2 Resp"),
]

def run(mode, pb1):
    props = [f"-Dreps={REPS}", f"-Droute.mode={mode}",
             f"-Droute.pb1={pb1}", "-Droute.window=true"]
    subprocess.run(["java", *props, "-cp", CP, MAIN],
                   cwd=BASE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   check=True)

def read_mean(fname):
    """Media della colonna 'mean' sulle finestre di campionamento."""
    path = os.path.join(STATS, fname)
    vals = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            vals.append(float(line.split()[0]))
    return sum(vals) / len(vals) if vals else 0.0

def collect():
    return {label: read_mean(fname) for fname, label in METRICS}

def main():
    print(f"== Confronto routing (REPS={REPS}) ==\n")
    print("[1] Baseline OVERFLOW (versione corrente)...")
    run("overflow", 0.0)
    base = collect()
    print("    " + "  ".join(f"{k}={v:.3f}" for k, v in base.items()) + "\n")

    rows = []
    for pb1 in PB1_GRID:
        print(f"[*] FIXED p_b1={pb1*100:.0f}%  (p_b2={100-pb1*100:.0f}%)...")
        run("fixed", pb1)
        cur = collect()
        # distanza relativa L1 normalizzata per metrica
        score = sum(abs(cur[k] - base[k]) / (abs(base[k]) + 1e-9) for k in base)
        rows.append((pb1, score, cur))
        print("    " + "  ".join(f"{k}={v:.3f}" for k, v in cur.items()))
        print(f"    -> distanza relativa = {score:.4f}\n")

    rows.sort(key=lambda x: x[1])
    print("=========== CLASSIFICA (piu' simile in alto) ===========")
    print(f"{'p_b1':>6} {'p_b2':>6} {'distanza':>10}")
    for pb1, score, _ in rows:
        print(f"{pb1*100:5.0f}% {100-pb1*100:5.0f}% {score:10.4f}")
    best = rows[0]
    print(f"\n>>> MIGLIOR MATCH con l'overflow: p_b1 = {best[0]*100:.0f}% "
          f"(Braccio Uno), p_b2 = {100-best[0]*100:.0f}% (Braccio Due)")

if __name__ == "__main__":
    main()
