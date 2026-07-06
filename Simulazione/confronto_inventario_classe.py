"""
confronto_inventario_classe.py  (STANDALONE, non tocca script/grafici esistenti)

Confronto a due pannelli del LIVELLO l(t) (solo livello, niente OOS) della classe
piu' rappresentativa del miglioramento dell'inventory: BASELINE (03) vs OTTIMO (06).
Default: Classe 2 (OTC), che passa da 57 a 3 OOS.

Output: confronto_inventario_classe2.png (nuovo file).
"""

import os, re, argparse
import numpy as np
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
STATS = os.path.join(HERE, "stats")

CLASS_NAMES = {1: "ricetta", 2: "OTC", 3: "integratori", 4: "dispositivi", 5: "galeniche"}


def load_level_dat(path):
    s_val = S_val = None
    times, levels, oos = [], [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                m = re.search(r"s=(\d+)\s+S=(\d+)", line)
                if m:
                    s_val, S_val = int(m.group(1)), int(m.group(2))
                continue
            p = line.replace(",", ".").split()
            if len(p) >= 2:
                try:
                    times.append(float(p[0])); levels.append(int(float(p[1])))
                    oos.append(int(float(p[2])) if len(p) >= 3 else 0)
                except ValueError:
                    continue
    return (s_val, S_val, np.array(times),
            np.array(levels, dtype=int), np.array(oos, dtype=int))


def decimate(times, levels, oos, max_points=6000):
    n = len(times)
    if n <= max_points:
        return np.arange(n)
    changes = np.where((np.diff(levels) != 0) | (np.diff(oos) != 0))[0] + 1
    uniform = np.arange(0, n, max(1, n // max_points))
    return np.unique(np.concatenate([[0, n - 1], changes, uniform]))


def draw_level(ax, path, color, titolo):
    s_val, S_val, t, lv, oos = load_level_dat(path)
    idx = decimate(t, lv, oos)
    th = t[idx] / 3600.0
    ax.step(th, lv[idx], where="post", color=color, linewidth=1.2, alpha=0.9,
            label="Livello l(t)")
    if s_val is not None:
        ax.axhline(s_val, color="#d62728", ls="--", lw=1.1, label=f"s = {s_val}")
    if S_val is not None:
        ax.axhline(S_val, color="#2ca02c", ls=":", lw=1.1, label=f"S = {S_val}")
    total_oos = int(oos[-1]) if len(oos) else 0
    ax.set_title(f"{titolo}  (s={s_val}, S={S_val})  —  OOS totali = {total_oos}",
                 fontsize=12, pad=8)
    ax.set_xlabel("Tempo (ore)", fontsize=11)
    ax.grid(True, linewidth=0.4, alpha=0.5)
    ax.legend(fontsize=9, framealpha=0.9, edgecolor="gray", loc="upper right")
    return S_val


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--class-id", type=int, default=2)
    ap.add_argument("--baseline", default=os.path.join(STATS, "03_baseline_finito", "dat"))
    ap.add_argument("--ottimo", default=os.path.join(STATS, "06_ottimizzato_finito", "dat"))
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    c = args.class_id
    nome = CLASS_NAMES.get(c, f"Classe {c}")
    fb = os.path.join(args.baseline, f"inventory_class{c}_level.dat")
    fo = os.path.join(args.ottimo, f"inventory_class{c}_level.dat")
    out = args.out or os.path.join(HERE, f"confronto_inventario_classe{c}.png")

    # layout VERTICALE (impilato): stretto in larghezza -> ok per il foglio LaTeX
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8, 8.5), sharex=True, sharey=True)
    S1 = draw_level(ax1, fb, "#8c8c8c", "Baseline")
    S2 = draw_level(ax2, fo, "#1f77b4", "Ottimizzato")
    ax1.set_xlabel("")                         # xlabel solo sul pannello in basso
    ax1.set_ylabel("Livello scorta", fontsize=11)
    ax2.set_ylabel("Livello scorta", fontsize=11)
    top = max(v for v in (S1, S2) if v is not None) + 3
    ax1.set_ylim(0, top)

    fig.suptitle(f"Inventario — Classe {c} ({nome}): livello l(t)  —  baseline vs ottimizzato",
                 fontsize=12, y=0.995)
    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(out, dpi=150, bbox_inches="tight")
    print(f"Scritto: {out}")


if __name__ == "__main__":
    main()
