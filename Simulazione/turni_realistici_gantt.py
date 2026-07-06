"""
turni_realistici_gantt.py  (STANDALONE, non tocca gli script/grafici esistenti)

Disegna SOLO i due Gantt (cassieri + magazzinieri) della turnazione realistica
4-6h / max 8h-con-pausa, con lo stesso stile di turni_cassieri_lambda.png
(pannelli 1 e 3). I cassieri che facevano 11-15 e 16-20 sono FUSI in un'unica
persona con pausa 15-16 (turno spezzato = 8h di lavoro).

Output: turni_realistici_8h.png (nuovo file).
"""

import os
import matplotlib.pyplot as plt
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "turni_realistici_8h.png")

HOURS = list(range(8, 20))          # 08:00 .. 19:00 (slot orari)
X_MIN, X_MAX = 8, 20


def shift_color(start):
    if start <= 8:   return "#378ADD"   # apertura
    if start <= 10:  return "#1D9E75"   # mattina
    if start <= 13:  return "#7F77DD"   # pranzo/spike
    if start <= 15:  return "#639922"   # pomeriggio
    return "#EF9F27"                     # sera


# ── TURNAZIONE ────────────────────────────────────────────────────────────
# breaks = ore in cui il dipendente e' in pausa (assente dalla copertura).
# C4 e M3 sono turni SPEZZATI 11-15 + 16-20 (pausa 15-16): due persone fuse.
CASSIERI = [
    {"name": "C1", "start": 8,  "end": 16, "breaks": [13]},
    {"name": "C2", "start": 8,  "end": 16, "breaks": [14]},
    {"name": "C3", "start": 9,  "end": 17, "breaks": [16]},
    {"name": "C4", "start": 11, "end": 20, "breaks": [15]},   # 11-15 + 16-20
    {"name": "C5", "start": 16, "end": 20, "breaks": []},
    {"name": "C6", "start": 16, "end": 20, "breaks": []},
    {"name": "C7", "start": 16, "end": 20, "breaks": []},
]
MAGAZZINIERI = [
    {"name": "M1", "start": 8,  "end": 14, "breaks": []},
    {"name": "M2", "start": 14, "end": 20, "breaks": []},
    {"name": "M3", "start": 11, "end": 20, "breaks": [15]},   # 11-15 + 16-20
]

for w in CASSIERI + MAGAZZINIERI:
    w["color"] = shift_color(w["start"])


def status_row(w):
    """1 = al lavoro, -1 = pausa, 0 = assente, per ogni ora di HOURS."""
    row = []
    for h in HOURS:
        if w["start"] <= h < w["end"]:
            row.append(-1 if h in w["breaks"] else 1)
        else:
            row.append(0)
    return row


def draw_gantt(ax, workers, title):
    n = len(workers)
    bar_h = 0.58
    ax.set_facecolor("white")

    for wi, w in enumerate(workers):
        y = n - 1 - wi
        row = status_row(w)
        for hi, h in enumerate(HOURS):
            s = row[hi]
            if s == 1:
                ax.barh(y, 1, left=h, height=bar_h, color=w["color"], zorder=3)
            elif s == -1:
                ax.barh(y, 1, left=h, height=bar_h, color="#E0E0E0",
                        zorder=3, edgecolor="#BBBBBB", linewidth=0.6)
        pause_h = [h for hi, h in enumerate(HOURS) if row[hi] == -1]
        label = f"{w['name']}  {w['start']:02d}:00–{w['end']:02d}:00"
        if pause_h:
            label += "  (pausa " + ", ".join(f"{p}:00" for p in pause_h) + ")"
        ax.text(X_MIN - 0.12, y, label, ha="right", va="center",
                fontsize=9, color="#333333")

    for h in range(X_MIN, X_MAX + 1):
        ax.axvline(h, color="#DDDDDD", linewidth=0.6, zorder=1)

    ax.set_xlim(X_MIN - 1, X_MAX)
    ax.set_ylim(-0.6, n - 0.4)
    ax.set_xticks(range(X_MIN, X_MAX + 1))
    ax.set_xticklabels([f"{h}:00" for h in range(X_MIN, X_MAX + 1)], fontsize=8.5)
    ax.set_yticks([])
    ax.spines[["top", "right", "left"]].set_visible(False)
    ax.spines["bottom"].set_color("#CCCCCC")
    ax.set_title(title, fontsize=12, fontweight="normal",
                 pad=10, loc="left", color="#222222")


def main():
    fig, axes = plt.subplots(
        2, 1, figsize=(14, 8),
        gridspec_kw={"height_ratios": [len(CASSIERI), len(MAGAZZINIERI)]},
        facecolor="white")
    fig.subplots_adjust(hspace=0.35)

    draw_gantt(axes[0], CASSIERI, "Turni cassieri")
    draw_gantt(axes[1], MAGAZZINIERI, "Turni magazzinieri")

    plt.savefig(OUT, dpi=150, bbox_inches="tight", facecolor="white")
    print(f"Scritto: {OUT}")


if __name__ == "__main__":
    main()
