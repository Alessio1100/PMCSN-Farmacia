"""
turni_cassieri.py
Costruisce e grafica la TURNAZIONE REALISTICA dei dipendenti a partire dallo
staffing ottimo per fascia prodotto da ConfigurationSearch (attivita' 05,
stats/05_infinito_configsearch/dat/best_staffing.tsv), confrontata con il
profilo di arrivo lambda(t) del sistema.

Lo staffing ottimo per fascia NON e' direttamente una turnazione: una sequenza
tipo (2,1) -> (5,2) -> (2,1) implicherebbe dipendenti che lavorano una sola ora.
Qui viene "smussato" in turni verosimili con queste regole:
  1. decomposizione LIFO del profilo in turni CONTIGUI (chi apre per coprire un
     picco chiude alla fine del picco -> i turni corti restano corti, i turni
     di base restano lunghi);
  2. durata MINIMA del turno (default 3h): i turni-spike vengono ESTESI verso
     le ore adiacenti a domanda piu' alta (sovra-copertura, mai sotto);
  3. durata MASSIMA del turno (default 8h): i turni piu' lunghi vengono spezzati
     in due persone (es. 8-16 e 16-20);
  4. la copertura risultante e' SEMPRE >= dello staffing ottimo per ogni fascia;
     il costo extra della sovra-copertura ("costo del realismo") e' stampato.

I turni generati vengono anche scritti in turni_generati_search.dat (stesso
formato META del vecchio file scritto a mano) cosi' si possono ritoccare a mano
e rigraficare con --dat.

Output di default: turni_da_configsearch.png (nome NUOVO: i vecchi
turni_cassieri.png / turni_cassieri_lambda.png restano per confronto).

Uso:
    python turni_cassieri.py                                  # genera dalla search 05
    python turni_cassieri.py --staffing path/best_staffing.tsv
    python turni_cassieri.py --min-shift 3 --max-shift 8
    python turni_cassieri.py --dat "turni_cassieri(2).dat"    # modalita' legacy: piano a mano
    python turni_cassieri.py --show
"""

import os, argparse
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

# ── PROFILO DI ARRIVO lambda(t) ───────────────────────────────────────
# = Simulation.arrivalRates (arrivi/s per slot da 30 min, 08:00-20:00, profilo
# RAW con picchi capati a 0.0255 — CLAUDE.md §6/§20.1). Tenere in sync col Java.
LAMBDA_SLOTS = [
    0.007050, 0.008615, 0.015500, 0.018335, 0.019800, 0.016200,
    0.022200, 0.025500, 0.024935, 0.020400, 0.012900, 0.015765,
    0.016135, 0.013200, 0.012000, 0.014665, 0.017700, 0.021635,
    0.024000, 0.025500, 0.025500, 0.024900, 0.025500, 0.020000,
]
LAMBDA_CLH = [r * 3600.0 for r in LAMBDA_SLOTS]   # clienti/h per slot da 30'

DEFAULT_STAFFING_TSV = os.path.join(
    HERE, "stats", "05_infinito_configsearch", "dat", "best_staffing.tsv")

COST_CASSA_H, COST_MAG_H = 26.0, 18.0   # €/h (CLAUDE.md §8)

# colore del turno in base all'ora d'inizio (stessa palette del piano a mano)
def shift_color(start):
    if start <= 8:   return "#378ADD"   # apertura
    if start <= 10:  return "#1D9E75"   # mattina
    if start <= 13:  return "#7F77DD"   # pranzo/spike
    if start <= 15:  return "#639922"   # pomeriggio
    return "#EF9F27"                     # sera


# ══════════════════ COSTRUZIONE TURNI DALLO STAFFING OTTIMO ══════════════════

def load_best_staffing(path, hours):
    """best_staffing.tsv (search 05) → richiesta per ora: (casse, magazzinieri)."""
    by_hour = {}
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) >= 3:
                by_hour[int(p[0].split("-")[0])] = (int(p[1]), int(p[2]))
    casse = [by_hour[h][0] for h in hours]
    magaz = [by_hour[h][1] for h in hours]
    return casse, magaz


def decompose_lifo(demand, hours):
    """Decomposizione LIFO del profilo in turni contigui [start, end).
    Quando la domanda cala chiude l'ULTIMO turno aperto (chi entra per il picco
    esce a fine picco): i turni di base restano lunghi, gli spike corti."""
    day_end = hours[-1] + 1
    active, shifts = [], []
    for hi, h in enumerate(hours):
        d = demand[hi]
        while len(active) > d:
            s = active.pop()
            s["end"] = h
            shifts.append(s)
        while len(active) < d:
            active.append({"start": h})
    for s in active:
        s["end"] = day_end
        shifts.append(s)
    return shifts


def enforce_min_max(shifts, demand, hours, min_len, max_len):
    """Realismo: turni < min_len estesi (verso l'ora adiacente a domanda piu' alta,
    solo sovra-copertura); turni > max_len spezzati in due persone."""
    day_start, day_end = hours[0], hours[-1] + 1
    dem = {h: d for h, d in zip(hours, demand)}

    # 1) split dei turni troppo lunghi
    out = []
    for s in shifts:
        seg = dict(s)
        while seg["end"] - seg["start"] > max_len:
            out.append({"start": seg["start"], "end": seg["start"] + max_len})
            seg = {"start": seg["start"] + max_len, "end": seg["end"]}
        out.append(seg)

    # 2) estensione dei turni troppo corti
    for s in out:
        while s["end"] - s["start"] < min_len:
            can_left  = s["start"] > day_start
            can_right = s["end"] < day_end
            if not can_left and not can_right:
                break
            dl = dem.get(s["start"] - 1, -1) if can_left else -1
            dr = dem.get(s["end"], -1) if can_right else -1
            if dr >= dl:            # estendi dove la domanda e' piu' alta
                s["end"] += 1
            else:
                s["start"] -= 1
    return out


def merge_chains(shifts, max_len):
    """Accorpa turni consecutivi (end == start) nella stessa persona se la durata
    totale resta <= max_len: meno dipendenti a parita' di copertura."""
    pool = sorted(shifts, key=lambda s: (s["start"], s["end"]))
    merged = []
    while pool:
        cur = pool.pop(0)
        again = True
        while again:
            again = False
            for i, nxt in enumerate(pool):
                if nxt["start"] == cur["end"] and (nxt["end"] - cur["start"]) <= max_len:
                    cur = {"start": cur["start"], "end": nxt["end"]}
                    pool.pop(i)
                    again = True
                    break
        merged.append(cur)
    return merged


def build_workers(demand, hours, prefix, min_len, max_len):
    """Dallo staffing per fascia ai lavoratori con turno contiguo realistico."""
    shifts = decompose_lifo(demand, hours)
    shifts = enforce_min_max(shifts, demand, hours, min_len, max_len)
    shifts = merge_chains(shifts, max_len)
    shifts.sort(key=lambda s: (s["start"], -(s["end"] - s["start"])))
    workers = []
    for i, s in enumerate(shifts, start=1):
        workers.append({"name": f"{prefix}{i}", "start": s["start"], "end": s["end"],
                        "color": shift_color(s["start"]),
                        "ruolo": "cassiere" if prefix == "C" else "magazziniere"})
    return workers


def coverage_of(workers, hours):
    return [sum(1 for w in workers if w["start"] <= h < w["end"]) for h in hours]


def write_generated_dat(path, workers_c, workers_m, hours, dem_c, dem_m):
    """Scrive i turni generati nello stesso formato META del piano a mano
    (ritoccabili a mano e rigraficabili con --dat)."""
    allw = workers_c + workers_m
    with open(path, "w", encoding="utf-8") as f:
        f.write("# Turni GENERATI dallo staffing ottimo di ConfigurationSearch (attivita' 05)\n")
        f.write("# smussati per realismo (min/max durata turno). Ritoccabili a mano.\n#\n")
        f.write("# META nome  inizio  fine  colore   ruolo\n")
        for w in allw:
            f.write(f"META {w['name']:<5} {w['start']:<7} {w['end']:<5} {w['color']}  {w['ruolo']}\n")
        f.write("#\n# ora  casse_richieste  magaz_richiesti  " +
                "  ".join(w["name"] for w in allw) + "\n")
        for hi, h in enumerate(hours):
            stati = ["1" if w["start"] <= h < w["end"] else "0" for w in allw]
            f.write(f"{h:<6} {dem_c[hi]:<16} {dem_m[hi]:<16} " + "   ".join(stati) + "\n")


# ══════════════════ LETTURA .dat A MANO (modalita' legacy) ══════════════════

def load_dat(path):
    meta, hours, demand_casse, demand_magaz, grid = [], [], [], [], []
    with open(path) as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("META"):
                parts = line.split()
                meta.append({"name": parts[1], "start": int(parts[2]),
                             "end": int(parts[3]), "color": parts[4],
                             "ruolo": parts[5]})
                continue
            if line.startswith("#") or not line:
                continue
            parts = list(map(int, line.split()))
            hours.append(parts[0])
            demand_casse.append(parts[1])
            demand_magaz.append(parts[2])
            grid.append(parts[3:])
    return meta, hours, demand_casse, demand_magaz, grid


# ══════════════════ DISEGNO ══════════════════

def draw_gantt(ax, workers, status, hours, title, x_min, x_max):
    n = len(workers)
    n_hours = len(hours)
    bar_h = 0.58
    ax.set_facecolor("white")

    for wi, w in enumerate(workers):
        y = n - 1 - wi
        row = status[wi]
        for hi, h in enumerate(hours):
            s = row[hi]
            if s == 1:
                ax.barh(y, 1, left=h, height=bar_h, color=w["color"], zorder=3)
            elif s == -1:
                ax.barh(y, 1, left=h, height=bar_h, color="#E0E0E0",
                        zorder=3, edgecolor="#BBBBBB", linewidth=0.6)
        pause_h = [hours[hi] for hi in range(n_hours) if row[hi] == -1]
        label = f"{w['name']}  {w['start']:02d}:00–{w['end']:02d}:00"
        if pause_h:
            label += f"  (pausa {pause_h[0]}:00)"
        ax.text(x_min - 0.12, y, label, ha="right", va="center",
                fontsize=9, color="#333333")

    for h in range(x_min, x_max + 1):
        ax.axvline(h, color="#DDDDDD", linewidth=0.6, zorder=1)

    ax.set_xlim(x_min - 1, x_max)
    ax.set_ylim(-0.6, n - 0.4)
    ax.set_xticks(range(x_min, x_max + 1))
    ax.set_xticklabels([f"{h}:00" for h in range(x_min, x_max + 1)], fontsize=8.5)
    ax.set_yticks([])
    ax.spines[["top", "right", "left"]].set_visible(False)
    ax.spines["bottom"].set_color("#CCCCCC")
    ax.set_title(title, fontsize=12, fontweight="normal",
                 pad=10, loc="left", color="#222222")


def draw_bar(ax, hours, demand, supply, title, lam=None,
             demand_label="Staffing ottimo (search 05)",
             supply_label="Copertura (turni realistici)"):
    n_hours = len(hours)
    x = np.arange(n_hours)
    w_b = 0.38
    bars_d = ax.bar(x - w_b/2, demand, width=w_b, color="#1D9E75",
                    alpha=0.85, label=demand_label, zorder=3)
    bars_s = ax.bar(x + w_b/2, supply, width=w_b, color="#378ADD",
                    alpha=0.85, label=supply_label, zorder=3)

    for bar in bars_d:
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.05,
                str(int(bar.get_height())), ha="center", va="bottom",
                fontsize=7.5, color="#333")
    for i, bar in enumerate(bars_s):
        v, d = int(bar.get_height()), demand[i]
        col = "#A32D2D" if v < d else ("#185FA5" if v > d else "#0F6E56")
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.05,
                str(v), ha="center", va="bottom",
                fontsize=7.5, color=col, fontweight="bold")

    for i in range(n_hours):
        if supply[i] < demand[i]:               # sotto-copertura: MAI atteso
            ax.axvspan(i - 0.5, i + 0.5, color="#FCEAEA", zorder=0)

    handles = [
        mpatches.Patch(color="#1D9E75", alpha=0.85, label=demand_label),
        mpatches.Patch(color="#378ADD", alpha=0.85, label=supply_label),
    ]

    y_top = max(max(demand), max(supply))
    y_max = y_top + 2
    ax.set_facecolor("white")
    ax.set_xticks(x)
    ax.set_xticklabels([f"{h}:00" for h in hours], fontsize=8.5)
    ax.set_xlim(-0.6, n_hours - 0.4)
    ax.set_ylim(0, y_max)
    ax.set_yticks(range(0, y_max))
    ax.yaxis.grid(True, color="#EEEEEE", zorder=0)
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color("#CCCCCC")
    ax.set_ylabel("N°", fontsize=9, color="#555")

    # λ(t) su asse secondario: step per slot da 30' (il "perché" del profilo)
    if lam is not None:
        ax2 = ax.twinx()
        xs = np.repeat([j * 0.5 - 0.5 for j in range(len(lam) + 1)], 2)[1:-1]
        ys = np.repeat(lam, 2)
        ln_lam, = ax2.plot(xs, ys, color="#555555", lw=1.6, alpha=0.9, zorder=4,
                           label="λ arrivi (clienti/h)")
        ax2.fill_between(xs, ys, color="#555555", alpha=0.07, zorder=1)
        ax2.set_ylim(0, max(lam) * 1.25)
        ax2.set_ylabel("λ  [clienti/h]", fontsize=9, color="#555")
        ax2.tick_params(labelsize=8, colors="#555")
        ax2.spines[["top"]].set_visible(False)
        handles.append(ln_lam)

    ax.legend(fontsize=8.5, frameon=False, loc="upper left", handles=handles)
    ax.set_title(title, fontsize=10, fontweight="normal",
                 loc="left", color="#444444", pad=8)


def make_figure(workers_c, workers_m, status_c, status_m, hours,
                dem_c, dem_m, sup_c, sup_m, out, show):
    x_min, x_max = min(hours), max(hours) + 1
    fig, axes = plt.subplots(
        4, 1, figsize=(14, 14),
        gridspec_kw={"height_ratios": [3, 1.5, 1.8, 1.5]},
        facecolor="white")
    fig.subplots_adjust(hspace=0.45)

    draw_gantt(axes[0], workers_c, status_c, hours, "Turni cassieri", x_min, x_max)
    draw_bar(axes[1], hours, dem_c, sup_c,
             "Copertura casse  vs  staffing ottimo (search 05)  vs  profilo di arrivo λ(t)",
             lam=LAMBDA_CLH)
    draw_gantt(axes[2], workers_m, status_m, hours, "Turni magazzinieri", x_min, x_max)
    draw_bar(axes[3], hours, dem_m, sup_m,
             "Copertura magazzino  vs  staffing ottimo (search 05)",
             lam=None)

    plt.savefig(out, dpi=150, bbox_inches="tight", facecolor="white")
    print(f"Scritto: {out}")
    if show:
        plt.show()


def status_matrix(workers, hours):
    return np.array([[1 if w["start"] <= h < w["end"] else 0 for h in hours]
                     for w in workers], dtype=int)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--staffing", default=DEFAULT_STAFFING_TSV,
                    help="best_staffing.tsv della search 05 (fonte dei turni)")
    ap.add_argument("--dat", default=None,
                    help="modalita' LEGACY: leggi un piano a mano in formato META invece di generare")
    ap.add_argument("--min-shift", type=int, default=3, help="durata minima turno in ore (default 3)")
    ap.add_argument("--max-shift", type=int, default=8, help="durata massima turno in ore (default 8)")
    ap.add_argument("--gen-dat", default=os.path.join(HERE, "turni_generati_search.dat"),
                    help="dove scrivere i turni generati in formato META (ritoccabili)")
    ap.add_argument("--out", default=None,
                    help="PNG di output (default: turni_da_configsearch.png; legacy: turni_cassieri_lambda.png)")
    ap.add_argument("--show", action="store_true", help="apri anche la finestra interattiva")
    args = ap.parse_args()

    if args.dat:
        # ── modalita' LEGACY: piano scritto a mano ────────────────────────────
        out = args.out or os.path.join(HERE, "turni_cassieri_lambda.png")
        meta, hours, dem_c, dem_m, grid = load_dat(args.dat)
        status = np.array(grid, dtype=int).T
        idx_c = [i for i, w in enumerate(meta) if w["ruolo"] == "cassiere"]
        idx_m = [i for i, w in enumerate(meta) if w["ruolo"] == "magazziniere"]
        sup_c = [(status[idx_c, hi] == 1).sum() for hi in range(len(hours))]
        sup_m = [(status[idx_m, hi] == 1).sum() for hi in range(len(hours))]
        make_figure([meta[i] for i in idx_c], [meta[i] for i in idx_m],
                    status[idx_c], status[idx_m], hours,
                    dem_c, dem_m, sup_c, sup_m, out, args.show)
        return

    # ── modalita' STANDARD: genera i turni dallo staffing ottimo della search ──
    out = args.out or os.path.join(HERE, "turni_da_configsearch.png")
    if not os.path.isfile(args.staffing):
        raise SystemExit(f"best_staffing.tsv non trovato: {args.staffing}\n"
                         f"→ esegui prima ConfigurationSearch (attivita' 05) o usa --dat per un piano a mano.")
    hours = list(range(8, 20))
    dem_c, dem_m = load_best_staffing(args.staffing, hours)
    print(f"Staffing ottimo (search 05) da: {args.staffing}")
    print(f"  casse : {dem_c}\n  magaz : {dem_m}")

    workers_c = build_workers(dem_c, hours, "C", args.min_shift, args.max_shift)
    workers_m = build_workers(dem_m, hours, "M", args.min_shift, args.max_shift)
    sup_c = coverage_of(workers_c, hours)
    sup_m = coverage_of(workers_m, hours)

    # sanity: la copertura non deve MAI stare sotto lo staffing ottimo
    assert all(s >= d for s, d in zip(sup_c, dem_c)), "sotto-copertura casse!"
    assert all(s >= d for s, d in zip(sup_m, dem_m)), "sotto-copertura magazzino!"

    print(f"\nTurni generati (min {args.min_shift}h, max {args.max_shift}h):")
    for w in workers_c + workers_m:
        print(f"  {w['name']:<4} {w['start']:02d}:00–{w['end']:02d}:00  ({w['end']-w['start']}h, {w['ruolo']})")

    # costo del realismo = ore di sovra-copertura x tariffa (CLAUDE.md §8)
    extra_c = sum(sup_c) - sum(dem_c)
    extra_m = sum(sup_m) - sum(dem_m)
    print(f"\nOre-cassiere:      ottimo={sum(dem_c)}  turni={sum(sup_c)}  (+{extra_c}h = +{extra_c*COST_CASSA_H:.0f} €/gg)")
    print(f"Ore-magazziniere:  ottimo={sum(dem_m)}  turni={sup_m and sum(sup_m)}  (+{extra_m}h = +{extra_m*COST_MAG_H:.0f} €/gg)")
    print(f"Costo del realismo totale: +{extra_c*COST_CASSA_H + extra_m*COST_MAG_H:.0f} €/gg "
          f"(sovra-copertura necessaria per turni contigui >= {args.min_shift}h)")

    write_generated_dat(args.gen_dat, workers_c, workers_m, hours, dem_c, dem_m)
    print(f"Turni scritti in: {args.gen_dat}  (ritoccabili a mano, poi --dat per rigraficare)")

    make_figure(workers_c, workers_m,
                status_matrix(workers_c, hours), status_matrix(workers_m, hours),
                hours, dem_c, dem_m, sup_c, sup_m, out, args.show)


if __name__ == "__main__":
    main()
