#!/usr/bin/env python3
"""
Grafico di selezione della politica (s,S) a partire dall'output di InventoryConfigSearch
(results.dat). Mostra che la configurazione scelta e' Pareto-ottima nel piano
COSTO inventario vs P(OOS) -- le due sole dimensioni che discriminano, dato che il
tempo di risposta E[T] e' praticamente costante su tutte le config (abbandoni off, §18).

Pannello SINISTRO : tutte le config, R=30 vs R=60 -> il cluster R=30 domina (meno OOS a meno costo).
Pannello DESTRO   : zoom sulla regione feasible R=30 (P(OOS) <= SLA), fronte di Pareto e config scelta.

Uso:
    python plot_inventory_search.py                       # legge ./results.dat, evidenzia 75,65,55,40,30 @R=30
    python plot_inventory_search.py path/results.dat
    python plot_inventory_search.py --target 75,65,55,40,30 --R 1800 --sla 0.05
Output: inventory_search_selection.png (accanto allo script).
"""
import os, sys, argparse

HERE = os.path.dirname(os.path.abspath(__file__))

# ---- colonne di results.dat ----
# 0..4 s1..s5 | 5 R_s | 6 f_init | 7 P_OOS | 8 cost_eur | 9 E_T_s | 10 maxLag_s | 11 fracLagOverR | 12 feasible
IDX = dict(s=slice(0,5), R=5, f=6, poos=7, cost=8, et=9, maxlag=10, frac=11)

def load(path):
    rows=[]
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line=line.strip()
            if not line or line.startswith("#"): continue
            p=line.split("\t")
            if len(p) < 12: continue
            rows.append([float(x) for x in p])
    if not rows:
        sys.exit(f"Nessun dato in {path}")
    return rows

def pareto_front(pts):
    """pts: lista di (x=P_OOS, y=cost, row). Ritorna i non-dominati (minimizzare entrambi), ordinati per x."""
    s=sorted(pts, key=lambda t:(t[0], t[1]))
    front=[]; best_cost=float("inf")
    for x,y,r in s:
        if y < best_cost - 1e-9:
            front.append((x,y,r)); best_cost=y
    return front

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("data", nargs="?", default=os.path.join(HERE,"dat","results.dat"))
    ap.add_argument("--target", default="75,65,55,40,30", help="s1..s5 della config da evidenziare")
    ap.add_argument("--R", type=int, default=1800, help="R (s) della config target")
    ap.add_argument("--f", type=float, default=1.0, help="f_init della config target")
    ap.add_argument("--sla", type=float, default=0.05, help="soglia SLA su P(OOS)")
    ap.add_argument("--out", default=os.path.join(HERE,"grafici","inventory_search_selection.png"))
    args=ap.parse_args()

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("matplotlib non installato: pip install matplotlib")

    rows=load(args.data)
    tgt=[int(x) for x in args.target.split(",")]
    def is_target(r):
        return ([int(v) for v in r[0:5]]==tgt and int(r[IDX['R']])==args.R
                and abs(r[IDX['f']]-args.f)<1e-6)
    target_row=next((r for r in rows if is_target(r)), None)

    R30=[r for r in rows if int(r[IDX['R']])==1800]
    R60=[r for r in rows if int(r[IDX['R']])==3600]
    feas=[r for r in R30 if r[IDX['poos']]<=args.sla]
    infeas=[r for r in R30 if r[IDX['poos']]>args.sla]

    def xs(rr): return [r[IDX['poos']]*100 for r in rr]
    def ys(rr): return [r[IDX['cost']] for r in rr]

    fig,(axL,axR)=plt.subplots(1,2,figsize=(14,6))

    # ---------- PANNELLO SINISTRO: R=30 vs R=60 ----------
    if R60:
        axL.scatter(xs(R60), ys(R60), s=26, c="#c0392b", marker="x", alpha=0.55, label=f"R=60 min (n={len(R60)})")
    axL.scatter(xs(R30), ys(R30), s=26, c="#2980b9", marker="o", alpha=0.55,
                edgecolors="none", label=f"R=30 min (n={len(R30)})")
    axL.axvline(args.sla*100, ls="--", c="k", lw=1.2)
    axL.text(args.sla*100, axL.get_ylim()[1], f" SLA {args.sla*100:.0f}%", va="top", ha="left", fontsize=9)
    if target_row:
        axL.scatter([target_row[IDX['poos']]*100],[target_row[IDX['cost']]], s=260, marker="*",
                    c="gold", edgecolors="k", linewidths=1.3, zorder=5, label="config scelta")
    axL.set_xlabel("P(out-of-stock)  [%]"); axL.set_ylabel("Costo inventario  [€/giorno]")
    axL.set_title("Tutte le configurazioni: R=30 domina R=60\n(meno OOS a costo inferiore)")
    axL.legend(loc="upper right", fontsize=9); axL.grid(alpha=0.25)

    # ---------- PANNELLO DESTRO: zoom R=30, feasibility + Pareto ----------
    axR.scatter(xs(infeas), ys(infeas), s=34, c="#c0392b", marker="o", alpha=0.45,
                edgecolors="none", label=f"R=30, P(OOS)>SLA (n={len(infeas)})")
    axR.scatter(xs(feas), ys(feas), s=40, c="#27ae60", marker="o", alpha=0.6,
                edgecolors="none", label=f"R=30, P(OOS)≤SLA (n={len(feas)})")
    # fronte di Pareto sulle R=30 (minimizza costo E OOS)
    front=pareto_front([(r[IDX['poos']], r[IDX['cost']], r) for r in R30])
    axR.plot([x*100 for x,_,_ in front],[y for _,y,_ in front], c="#8e44ad", lw=2.0,
             marker="D", ms=5, zorder=4, label="fronte di Pareto")
    axR.axvline(args.sla*100, ls="--", c="k", lw=1.2)
    axR.text(args.sla*100, axR.get_ylim()[0], f" SLA {args.sla*100:.0f}%", va="bottom", ha="left", fontsize=9)

    if target_row:
        tx=target_row[IDX['poos']]*100; ty=target_row[IDX['cost']]
        axR.scatter([tx],[ty], s=320, marker="*", c="gold", edgecolors="k", linewidths=1.4, zorder=6,
                    label="config scelta")
        # rank
        by_cost=sorted(feas, key=lambda r:r[IDX['cost']])
        by_oos =sorted(R30 , key=lambda r:r[IDX['poos']])
        rc=[i for i,r in enumerate(by_cost) if is_target(r)][0]+1
        ro=[i for i,r in enumerate(by_oos ) if is_target(r)][0]+1
        txt=(f"s={tgt}\nP(OOS)={target_row[IDX['poos']]*100:.2f}%  (min: {ro}/{len(R30)})\n"
             f"costo={ty:.0f} €/gg  ({rc}/{len(feas)} tra i feasible)")
        axR.annotate(txt, (tx,ty), textcoords="offset points", xytext=(14,10), fontsize=9,
                     bbox=dict(boxstyle="round,pad=0.4", fc="#fffbe6", ec="k", lw=0.8),
                     arrowprops=dict(arrowstyle="->", lw=1.0))
    axR.set_xlabel("P(out-of-stock)  [%]"); axR.set_ylabel("Costo inventario  [€/giorno]")
    axR.set_title("Regione feasible R=30: la config scelta è sul fronte di Pareto\n(minimo OOS a costo quasi-minimo)")
    axR.legend(loc="upper right", fontsize=9); axR.grid(alpha=0.25)

    fig.suptitle("Selezione politica (s,S) — InventoryConfigSearch", fontsize=13, fontweight="bold")
    fig.tight_layout(rect=(0,0,1,0.96))
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    fig.savefig(args.out, dpi=130)
    print(f"Scritto: {args.out}")
    if target_row:
        print(f"Config scelta s={tgt} R={args.R}: P(OOS)={target_row[IDX['poos']]*100:.2f}%  "
              f"costo={target_row[IDX['cost']]:.1f} €/gg  E[T]={target_row[IDX['et']]:.1f}s  "
              f"maxLag={target_row[IDX['maxlag']]/60:.1f}min")

if __name__=="__main__":
    main()
