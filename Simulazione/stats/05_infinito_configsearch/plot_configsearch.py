#!/usr/bin/env python3
"""
Grafici di selezione dello STAFFING a partire dall'output di ConfigurationSearch
(dat/results.dat).

METRICA (CLAUDE.md §23): tutto monetizzato in EUR/giorno-equivalente,
    C_tot = C_lavoro (26/18 EUR/h, §8) + C_inventario (holding+shortage+order, §20.3)
          + C_attesa (c_w · N_clienti · E[T], costo del tempo del cliente)
con vincolo HARD P(OOS) <= SLA (default 5%). La config scelta per fascia = min C_tot
tra quelle che rispettano la SLA (best-effort min C_tot se nessuna la rispetta).

FIGURA 1 (principale, la piu' interpretabile): per ogni fascia, barre IMPILATE della
scomposizione di C_tot (lavoro / inventario / attesa) per ciascuna config (nCasse,nMag).
  - barra col bordo ROSSO + P(OOS)% in rosso = config che VIOLA la SLA sull'OOS;
  - barre tagliate dal bordo alto (attesa esplosiva, config instabile) annotate col totale;
  - stella = config scelta (min C_tot sotto SLA).
Si VEDE il trade-off: a sinistra (staffing magro) domina l'attesa/shortage, a destra
(staffing pieno) domina il lavoro; il minimo sta in mezzo.

FIGURA 2 (verifica): fronte di Pareto C_tot vs E[T] per fascia (scala log in x);
punti ROSSI = E[T] > --rt-threshold (default 600 s).

Uso:
    python plot_configsearch.py                      # legge ./dat/results.dat
    python plot_configsearch.py path/results.dat
    python plot_configsearch.py --sla 0.05 --rt-threshold 600
    python plot_configsearch.py --staffing dat/best_staffing.tsv   # stella dal tsv invece che ricalcolata
Output: grafici/configsearch_breakdown.png + grafici/configsearch_pareto.png
"""
import os, sys, argparse
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

# Colonne di results.dat (vedi header nel file):
# 0 hour | 1 lambda | 2 numCasse | 3 numMag | 4 E_T_s | 5 P_OOS | 6 P_aband | 7 throughput_s |
# 8 laborCost | 9 invCost | 10 waitCost | 11 totalCost | 12..18 util_* | 19 util_B1_high | 20 util_B1_low
# NB: negli accessi sotto gli indici sono -1 perche' load() separa 'hour' dai valori numerici.
COL = dict(lam=1, casse=2, mag=3, et=4, poos=5, aband=6, thr=7,
           labor=8, inv=9, wait=10, cost=11)

HOUR_ORDER = ["08-09", "09-10", "10-11", "11-12", "12-13", "13-14",
              "14-15", "15-16", "16-17", "17-18", "18-19", "19-20"]

C_LABOR, C_INV, C_WAIT = "#4472C4", "#ED7D31", "#70AD47"


def load(path):
    rows = defaultdict(list)
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) < 12:
                continue
            rows[p[0]].append([float(x) for x in p[1:]])
    if not rows:
        sys.exit(f"Nessun dato in {path}")
    return rows


def load_staffing(path):
    """hour -> (nCasse, nMag) dello staffing scelto (facoltativo, per overlay)."""
    chosen = {}
    if not path:
        return chosen
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) >= 3:
                chosen[p[0]] = (int(p[1]), int(p[2]))
    return chosen


def g(r, key):
    return r[COL[key] - 1]   # -1: 'hour' rimosso da load()


STABILITY_MIN_X_RATIO = 0.90   # config stabile se throughput X >= 90% della lambda offerta


def is_stable(r):
    return g(r, 'thr') >= STABILITY_MIN_X_RATIO * g(r, 'lam')


def pick_best(rows, sla):
    """Stessa regola di ConfigurationSearch.printBestUnderSla: min C_tot tra le feasible
    (P(OOS)<=sla E stabile: X>=90% lambda — il P(OOS) basso di una config instabile e' un
    artefatto, la domanda resta bloccata in coda), altrimenti best-effort min C_tot su tutte."""
    feas = [r for r in rows if g(r, 'poos') <= sla and is_stable(r)]
    if feas:
        return min(feas, key=lambda r: g(r, 'cost')), True
    return min(rows, key=lambda r: g(r, 'cost')), False


def pareto_front(pts):
    s = sorted(pts, key=lambda t: (t[0], t[1]))
    front, best = [], float("inf")
    for x, y, r in s:
        if y < best - 1e-9:
            front.append((x, y, r)); best = y
    return front


def fig_breakdown(plt, hours, by_hour, chosen_map, sla, out):
    ncols, nrows = 4, (len(hours) + 3) // 4
    fig, axes = plt.subplots(nrows, ncols, figsize=(4.8 * ncols, 4.1 * nrows))
    axes = axes.flatten()

    for i, hour in enumerate(hours):
        ax = axes[i]
        rows = sorted(by_hour[hour], key=lambda r: (g(r, 'casse'), g(r, 'mag')))
        labels = [f"{int(g(r,'casse'))},{int(g(r,'mag'))}" for r in rows]
        labor  = [g(r, 'labor') for r in rows]
        inv    = [g(r, 'inv')   for r in rows]
        wait   = [g(r, 'wait')  for r in rows]
        totals = [g(r, 'cost')  for r in rows]
        viol   = [g(r, 'poos') > sla for r in rows]

        # y-lim: taglia le barre delle config instabili (attesa fuori scala) per non
        # schiacciare le altre; le tagliate sono annotate col loro totale.
        tmin = min(totals)
        sane = [t for t in totals if t <= 6 * tmin] or totals
        ymax = 1.30 * max(sane)

        x = range(len(rows))
        ax.bar(x, labor, color=C_LABOR, label="lavoro")
        ax.bar(x, inv, bottom=labor, color=C_INV, label="inventario (h+shortage+ord)")
        ax.bar(x, wait, bottom=[a + b for a, b in zip(labor, inv)], color=C_WAIT,
               label="attesa clienti")

        # bordo rosso + P(OOS)% per chi viola la SLA; tratteggio grigio per le instabili
        # (X<90%·λ: il loro P(OOS) basso è un artefatto); totale annotato sulle barre tagliate
        for k, r in enumerate(rows):
            if viol[k]:
                ax.bar([k], [min(totals[k], ymax)], fill=False, edgecolor="#c0392b",
                       linewidth=1.8, zorder=5)
                ax.text(k, min(totals[k], ymax * 0.86), f"OOS\n{g(r,'poos')*100:.1f}%",
                        ha="center", va="top", fontsize=6.5, color="#c0392b", fontweight="bold")
            elif not is_stable(r):
                ax.bar([k], [min(totals[k], ymax)], fill=False, edgecolor="#7f8c8d",
                       linewidth=1.4, linestyle="--", zorder=5)
                ax.text(k, min(totals[k], ymax) * 0.5, "instabile",
                        ha="center", va="center", fontsize=6.5, color="#555",
                        rotation=90, fontweight="bold")
            if totals[k] > ymax:
                ax.text(k, ymax * 0.99, f"{totals[k]/1000:.0f}k€",
                        ha="center", va="top", fontsize=7, rotation=90, color="#333")

        # stella sulla config scelta
        nc_nm = chosen_map.get(hour)
        if nc_nm:
            for k, r in enumerate(rows):
                if (int(g(r, 'casse')), int(g(r, 'mag'))) == nc_nm:
                    ax.plot(k, min(totals[k], ymax) + ymax * 0.03, marker="*", ms=17,
                            mfc="gold", mec="k", mew=1.1, clip_on=False, zorder=6)

        ax.set_ylim(0, ymax * 1.09)
        ax.set_xticks(list(x)); ax.set_xticklabels(labels, fontsize=7.5)
        ax.set_title(f"{hour}  ({g(rows[0],'lam')*3600:.0f} cl/h)", fontsize=10)
        ax.set_xlabel("config (casse, mag)", fontsize=8)
        ax.set_ylabel("C_tot [€/gg eq.]", fontsize=8)
        ax.tick_params(labelsize=7); ax.grid(axis="y", alpha=0.25)
        if i == 0:
            ax.legend(fontsize=6.5, loc="upper left")

    for j in range(len(hours), len(axes)):
        axes[j].axis("off")
    fig.suptitle("Scomposizione del costo totale per configurazione — ★ = scelta (min C_tot sotto SLA)\n"
                 "bordo rosso = viola SLA OOS  ·  tratteggio grigio = instabile (X<90%·λ)",
                 fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.955))
    fig.savefig(out, dpi=130)
    print(f"Scritto: {out}")


def fig_pareto(plt, hours, by_hour, chosen_map, sla, out):
    """Fronte di Pareto C_tot vs E[T]. Le config INSTABILI (X<90%·λ) hanno E[T] che esplode a
    1e5–1e6 s e costo milionario: se plottate alla loro ascissa/ordinata reale comprimono tutti i
    punti utili in un angolo. Quindi si FOCALIZZANO gli assi sui soli punti stabili e le instabili
    si CLAMPANO al bordo destro come triangoli (presenti ma non deformanti)."""
    ncols, nrows = 4, (len(hours) + 3) // 4
    fig, axes = plt.subplots(nrows, ncols, figsize=(4.6 * ncols, 4.0 * nrows))
    axes = axes.flatten()

    for i, hour in enumerate(hours):
        ax = axes[i]
        rows = by_hour[hour]

        # "In scala" = stabile (X≥90%·λ) E con E[T] entro un tetto relativo al minimo di fascia.
        # Il secondo filtro cattura le config al limite (ρ≈1: throughput ~ok ma E[T] esplode a
        # 1e4–1e6 s), che altrimenti — pur "stabili" — comprimerebbero l'asse come le instabili.
        # Il gap verso gli outlier è sempre enorme (>20×), quindi il fattore 8 separa netto.
        et_floor = min(g(r, 'et') for r in rows)
        et_cap = et_floor * 8.0
        onscale  = [r for r in rows if is_stable(r) and g(r, 'et') <= et_cap]
        on_ids   = {id(r) for r in onscale}
        offscale = [r for r in rows if id(r) not in on_ids]
        if not onscale:                       # nessun punto in scala: fallback progressivi
            onscale = [r for r in rows if is_stable(r)] or rows
            on_ids  = {id(r) for r in onscale}
            offscale = [r for r in rows if id(r) not in on_ids]

        et_on   = [g(r, 'et')   for r in onscale]
        cost_on = [g(r, 'cost') for r in onscale]
        xmin, xmax = min(et_on), max(et_on)
        ymin, ymax = min(cost_on), max(cost_on)
        pad = 1.18
        xclamp  = xmax * pad                  # ascissa dei triangoli fuori scala
        xlim_hi = xmax * (pad ** 2 if offscale else pad)
        yclip   = ymax * 1.15                 # tetto y per non far esplodere l'asse

        # stabili: feasible (OOS≤SLA) vs viola SLA
        feas = [r for r in onscale if g(r, 'poos') <= sla]
        viol = [r for r in onscale if g(r, 'poos') >  sla]
        if feas:
            ax.scatter([g(r,'et') for r in feas], [g(r,'cost') for r in feas], s=50,
                       c="#2980b9", alpha=0.75, edgecolors="none", label="feasible (OOS≤SLA)")
        if viol:
            ax.scatter([g(r,'et') for r in viol], [g(r,'cost') for r in viol], s=50,
                       c="#e67e22", alpha=0.8, edgecolors="none", label="viola SLA OOS")

        # Pareto front SOLO sui punti stabili (i fuori scala sono dominati e irrilevanti)
        front = pareto_front([(g(r,'et'), g(r,'cost'), r) for r in onscale])
        ax.plot([p[0] for p in front], [p[1] for p in front], c="#8e44ad", lw=1.8,
                marker="D", ms=5, zorder=4, label="Pareto")
        for x, y, r in front:
            ax.annotate(f"{int(g(r,'casse'))},{int(g(r,'mag'))}", (x, y),
                        textcoords="offset points", xytext=(5, 4), fontsize=7.5)

        # instabili: triangolo al bordo destro (E[T] e costo clampati), con conteggio
        if offscale:
            for r in offscale:
                ax.scatter([xclamp], [min(g(r,'cost'), yclip)], s=42, marker=">",
                           c="#c0392b", alpha=0.55, edgecolors="none", zorder=3)
            ax.text(xclamp, ymin, f"{len(offscale)} instabili\n(E[T]≫, fuori scala) →",
                    fontsize=6.3, color="#c0392b", ha="right", va="bottom")

        # scelta (stella)
        nc_nm = chosen_map.get(hour)
        if nc_nm:
            m = [r for r in rows if (int(g(r,'casse')), int(g(r,'mag'))) == nc_nm]
            if m:
                ax.scatter([min(g(m[0],'et'), xclamp)], [min(g(m[0],'cost'), yclip)], s=240,
                           marker="*", c="gold", edgecolors="k", linewidths=1.2, zorder=6,
                           label="scelta")

        ax.set_xscale("log")
        ax.set_xlim(xmin / pad, xlim_hi)
        ax.set_ylim(ymin * 0.90, ymax * 1.18)
        ax.set_title(f"{hour}  ({g(rows[0],'lam')*3600:.0f} cl/h)", fontsize=10)
        ax.set_xlabel("E[T] sistema [s]  (log)", fontsize=8)
        ax.set_ylabel("C_tot [€/gg eq.]", fontsize=8)
        ax.tick_params(labelsize=7); ax.grid(alpha=0.25)
        if i == 0:
            ax.legend(fontsize=7, loc="best")

    for j in range(len(hours), len(axes)):
        axes[j].axis("off")
    fig.suptitle("Fronte di Pareto C_tot vs E[T] per fascia (verifica della scelta)\n"
                 "assi focalizzati sulle config STABILI · ▷ = instabili (X<90%·λ) clampate al bordo",
                 fontsize=12, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.955))
    fig.savefig(out, dpi=130)
    print(f"Scritto: {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", nargs="?", default=os.path.join(HERE, "dat", "results.dat"))
    ap.add_argument("--sla", type=float, default=0.05, help="SLA su P(OOS) (default 0.05)")
    ap.add_argument("--rt-threshold", type=float, default=600.0,
                    help="soglia E[T] per i punti rossi nel Pareto (default 600 s)")
    ap.add_argument("--staffing", default=None,
                    help="tsv 'hour\\tnCasse\\tnMag' (es. dat/best_staffing.tsv): stella da qui invece che ricalcolata")
    ap.add_argument("--out-dir", default=os.path.join(HERE, "grafici"))
    args = ap.parse_args()

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("matplotlib non installato: pip install matplotlib")

    by_hour = load(args.data)
    hours = [h for h in HOUR_ORDER if h in by_hour] or list(by_hour.keys())

    # Config scelta per fascia: dal tsv se fornito, altrimenti stessa regola del Java.
    chosen_map = load_staffing(args.staffing)
    print(f"{'Fascia':<8}{'scelta':>8}{'E[T](s)':>10}{'P(OOS)':>9}{'C_tot':>10}{'lav':>8}{'inv':>8}{'att':>8}  status")
    print("-" * 80)
    for hour in hours:
        rows = by_hour[hour]
        if hour in chosen_map:
            nc, nm = chosen_map[hour]
            m = [r for r in rows if (int(g(r,'casse')), int(g(r,'mag'))) == (nc, nm)]
            best, feas = (m[0], g(m[0],'poos') <= args.sla) if m else pick_best(rows, args.sla)
        else:
            best, feas = pick_best(rows, args.sla)
            chosen_map[hour] = (int(g(best,'casse')), int(g(best,'mag')))
        print(f"{hour:<8}{f'{chosen_map[hour][0]},{chosen_map[hour][1]}':>8}"
              f"{g(best,'et'):>10.1f}{g(best,'poos'):>9.4f}{g(best,'cost'):>10.1f}"
              f"{g(best,'labor'):>8.0f}{g(best,'inv'):>8.1f}{g(best,'wait'):>8.1f}"
              f"  {'feasible' if feas else 'BEST-EFFORT (P(OOS)>SLA)'}")

    os.makedirs(args.out_dir, exist_ok=True)
    fig_breakdown(plt, hours, by_hour, chosen_map, args.sla,
                  os.path.join(args.out_dir, "configsearch_breakdown.png"))
    fig_pareto(plt, hours, by_hour, chosen_map, args.sla,
               os.path.join(args.out_dir, "configsearch_pareto.png"))


if __name__ == "__main__":
    main()
