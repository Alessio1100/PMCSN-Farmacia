#!/usr/bin/env python3
"""
Confronto finale a 3 scenari (attivita' 08):
    Baseline (03)  vs  Ottimizzato (06)  vs  Ottimizzato + turni realistici (07)

Per OGNI statistica per-centro presente nei dat/ dei tre scenari (formato
FiniteHorizonSimulation: 96 finestre di campionamento x colonne mean/lower/upper)
produce un grafico di overlay con le 3 curve (solo medie, niente bande IC:
convenzione CLAUDE.md §20.6 — gli IC vanno in tabella).

In piu' produce il quadro dei COSTI (confronto_costi.png + confronto_costi.dat):
  - costo personale + inventario per scenario (barre impilate, delta % vs baseline);
  - P(OOS) per scenario con la linea SLA 5%;
  - E[T] di sistema medio per scenario.
Fonte: summary.dat scritto da FiniteHorizonSimulation in ogni dat/ (KPI aggregati)
+ system_Response.dat per E[T].

Uso:
    python confronto_scenari.py                      # legge 03/06/07 dalle cartelle standard
    python confronto_scenari.py --dirs D1 D2 D3      # override cartelle dat (ordine: base, ott, turni)
    python confronto_scenari.py --sla 0.05
Output: grafici in ./grafici/ + confronto_costi.dat accanto allo script.
"""
import os, sys, glob, argparse

HERE  = os.path.dirname(os.path.abspath(__file__))
STATS = os.path.dirname(HERE)

SCENARIOS = [
    ("Baseline",            os.path.join(STATS, "03_baseline_finito",        "dat"), "#7f8c8d"),
    ("Ottimizzato",         os.path.join(STATS, "06_ottimizzato_finito",     "dat"), "#2980b9"),
    ("Ottimizzato + turni", os.path.join(STATS, "07_ottimizzato_turni_finito","dat"), "#e67e22"),
]

# file di servizio da NON trattare come statistiche per-finestra
EXCLUDE_PREFIX = ("inventory_class", "best_", "turni_")
EXCLUDE_FILES  = ("summary.dat", "results.dat", "best_configs.dat")

# unita' di misura per l'asse y in base al suffisso <Metrica> del file
YLABEL = {
    "Response":            "E[T]  [s]",
    "Wait":                "E[Tq] coda  [s]",
    "Node":                "E[N]  [job]",
    "Queue":               "E[Nq]  [job]",
    "Utilization":         "ρ",
    "avgInterarrivals":    "interarrivo  [s]",
    "lossProbability":     "P(loss)",
    "arrivalsPerWindow":   "arrivi / finestra",
    "completionsPerWindow":"completamenti / finestra",
    "abandonPerWindow":    "abbandoni / finestra",
}


# Nomi parlanti per le due sotto-code del Braccio Uno (titolo + file PNG); i .dat
# restano invariati (standard di progetto). HIGH=carico rifornimento, LOW=prelievo cliente.
CENTER_LABELS = {
    "Braccio Uno HIGH": "Braccio Uno — Carico",
    "Braccio Uno LOW":  "Braccio Uno — Prelievo",
}
FILE_CENTER_RENAME = {
    "Braccio Uno HIGH": "Braccio Uno Carico",
    "Braccio Uno LOW":  "Braccio Uno Prelievo",
}


def read_3col(path):
    """Legge un .dat mean/lower/upper → lista di mean (una per finestra)."""
    means = []
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            try:
                means.append(float(p[0]))
            except ValueError:
                continue
    return means


def read_summary(path):
    """summary.dat → dict chiave->valore (stringhe per scenario/reps, float per il resto)."""
    if not os.path.isfile(path):
        return None
    out = {}
    with open(path, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) < 2:
                continue
            try:
                out[p[0]] = float(p[1])
            except ValueError:
                out[p[0]] = p[1]
    return out


def stat_files(d):
    """Nomi dei .dat 'per-finestra' in una cartella (esclusi i file di servizio)."""
    names = set()
    for f in glob.glob(os.path.join(d, "*.dat")):
        b = os.path.basename(f)
        if b in EXCLUDE_FILES or any(b.startswith(p) for p in EXCLUDE_PREFIX):
            continue
        names.add(b)
    return names


def time_axis(n):
    """n finestre uniformi sulla giornata 08:00-20:00 → ascissa in ore (centro finestra)."""
    return [8.0 + (j + 0.5) * 12.0 / n for j in range(n)]


def plot_stat(plt, fname, series, out_dir):
    """Overlay delle 3 curve (solo medie) per una statistica."""
    base = fname[:-4]                       # senza .dat
    centro, _, metrica = base.rpartition("_")
    clabel   = CENTER_LABELS.get(centro, centro)
    out_base = f"{FILE_CENTER_RENAME.get(centro, centro)}_{metrica}"
    fig, ax = plt.subplots(figsize=(9, 4.6))
    for (label, _, color), means in series:
        if means is None:
            continue
        ax.plot(time_axis(len(means)), means, color=color, lw=1.7, label=label)
    if metrica == "Utilization":
        ax.set_ylim(0, 1.05)
    ax.set_xlim(8, 20)
    ax.set_xticks(range(8, 21))
    ax.set_xticklabels([f"{h}:00" for h in range(8, 21)], fontsize=8)
    ax.set_xlabel("ora del giorno", fontsize=9)
    ax.set_ylabel(YLABEL.get(metrica, metrica), fontsize=9)
    ax.set_title(f"{clabel} — {metrica}", fontsize=11)
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8.5)
    fig.tight_layout()
    fig.savefig(os.path.join(out_dir, out_base + ".png"), dpi=120)
    plt.close(fig)


def fig_costs(plt, rows, sla, out_dir):
    """Quadro dei costi: barre impilate personale+inventario, P(OOS) vs SLA, E[T] sistema."""
    labels = [r["label"] for r in rows]
    colors = [r["color"] for r in rows]
    x = range(len(rows))

    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(15, 5.2))

    # ── (1) costi impilati ──
    pers = [r["pers"] for r in rows]
    inv  = [r["inv"]  for r in rows]
    tot  = [r["tot"]  for r in rows]
    ax1.bar(x, pers, color="#4472C4", label="personale")
    ax1.bar(x, inv, bottom=pers, color="#ED7D31", label="inventario")
    base_tot = tot[0]
    for i in x:
        delta = (tot[i] - base_tot) / base_tot * 100.0
        note = f"{tot[i]:.0f} €" + (f"\n({delta:+.1f}%)" if i > 0 else "")
        ax1.text(i, tot[i] + max(tot) * 0.015, note, ha="center", va="bottom",
                 fontsize=9, fontweight="bold")
    ax1.set_xticks(list(x)); ax1.set_xticklabels(labels, fontsize=9)
    ax1.set_ylabel("costo  [€/giorno]", fontsize=9)
    ax1.set_ylim(0, max(tot) * 1.18)
    ax1.set_title("Costo giornaliero (personale + inventario)", fontsize=10.5)
    ax1.grid(axis="y", alpha=0.25); ax1.legend(fontsize=8.5)

    # ── (2) P(OOS) vs SLA ──
    poos = [r["p_oos"] * 100 for r in rows]
    ax2.bar(x, poos, color=colors, alpha=0.85)
    ax2.axhline(sla * 100, ls="--", c="#c0392b", lw=1.4)
    ax2.text(len(rows) - 0.55, sla * 100, f" SLA {sla*100:.0f}%", color="#c0392b",
             fontsize=9, va="bottom", ha="right")
    for i in x:
        ax2.text(i, poos[i] + max(poos + [sla * 100]) * 0.015, f"{poos[i]:.2f}%",
                 ha="center", va="bottom", fontsize=9, fontweight="bold")
    ax2.set_xticks(list(x)); ax2.set_xticklabels(labels, fontsize=9)
    ax2.set_ylabel("P(out-of-stock)  [%]", fontsize=9)
    ax2.set_ylim(0, max(poos + [sla * 100]) * 1.25)
    ax2.set_title("SLA di servizio: P(OOS) per-articolo", fontsize=10.5)
    ax2.grid(axis="y", alpha=0.25)

    # ── (3) E[T] sistema ──
    et = [r["e_t"] for r in rows]
    ax3.bar(x, et, color=colors, alpha=0.85)
    for i in x:
        delta = (et[i] - et[0]) / et[0] * 100.0 if et[0] > 0 else 0.0
        note = f"{et[i]:.0f} s" + (f"\n({delta:+.1f}%)" if i > 0 else "")
        ax3.text(i, et[i] + max(et) * 0.015, note, ha="center", va="bottom",
                 fontsize=9, fontweight="bold")
    ax3.set_xticks(list(x)); ax3.set_xticklabels(labels, fontsize=9)
    ax3.set_ylabel("E[T] sistema  [s]", fontsize=9)
    ax3.set_ylim(0, max(et) * 1.2)
    ax3.set_title("Tempo di risposta end-to-end (media giornata)", fontsize=10.5)
    ax3.grid(axis="y", alpha=0.25)

    fig.suptitle("Confronto scenari — Baseline vs Ottimizzato vs Ottimizzato+turni "
                 "(orizzonte finito, medie per replica)", fontsize=12.5, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    out = os.path.join(out_dir, "confronto_costi.png")
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print(f"Scritto: {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dirs", nargs=3, default=None,
                    help="override cartelle dat (ordine: baseline, ottimizzato, ottimizzato+turni)")
    ap.add_argument("--sla", type=float, default=0.05, help="SLA su P(OOS) (default 0.05)")
    ap.add_argument("--out-dir", default=os.path.join(HERE, "grafici"))
    args = ap.parse_args()

    scen = SCENARIOS
    if args.dirs:
        scen = [(SCENARIOS[i][0], args.dirs[i], SCENARIOS[i][2]) for i in range(3)]

    for label, d, _ in scen:
        if not os.path.isdir(d):
            sys.exit(f"Cartella dat mancante per '{label}': {d}\n"
                     f"-> esegui prima FiniteHorizonSimulation.{{Baseline,Ottimizzato,Ottimizzato_Turni}}.")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        sys.exit("matplotlib non installato: pip install matplotlib")

    os.makedirs(args.out_dir, exist_ok=True)

    # ── overlay per ogni statistica presente in TUTTI e tre gli scenari ──
    common = stat_files(scen[0][1]) & stat_files(scen[1][1]) & stat_files(scen[2][1])
    if not common:
        print("[ATTENZIONE] Nessuna statistica in comune tra le 3 cartelle: "
              "controlla di aver (ri)eseguito le tre run col naming nuovo (Response/Wait/...).")
    for fname in sorted(common):
        series = [((label, d, color), read_3col(os.path.join(d, fname)))
                  for label, d, color in scen]
        plot_stat(plt, fname, series, args.out_dir)
    print(f"Grafici overlay: {len(common)} statistiche -> {args.out_dir}")

    # ── quadro costi da summary.dat (+ E[T] da system_Response.dat) ──
    rows = []
    for label, d, color in scen:
        s = read_summary(os.path.join(d, "summary.dat"))
        if s is None:
            print(f"[ATTENZIONE] summary.dat mancante in {d}: quadro costi saltato "
                  f"(ri-esegui la run con il codice aggiornato).")
            rows = None
            break
        resp = read_3col(os.path.join(d, "system_Response.dat"))
        e_t = sum(resp) / len(resp) if resp else 0.0
        rows.append(dict(label=label, color=color,
                         pers=s["costo_personale_eur_gg"], inv=s["costo_inventario_eur_gg"],
                         tot=s["costo_totale_eur_gg"], p_oos=s["p_oos"], e_t=e_t,
                         lag=s.get("delivery_lag_min", 0.0)))

    if rows:
        fig_costs(plt, rows, args.sla, args.out_dir)

        # tabella riassuntiva (per la relazione: IC come ± si aggiungono a mano dalle stampe Java)
        tab = os.path.join(HERE, "confronto_costi.dat")
        with open(tab, "w", encoding="utf-8") as f:
            f.write("# scenario\tpersonale_eur_gg\tinventario_eur_gg\ttotale_eur_gg\t"
                    "delta_costo_vs_baseline\tP_OOS\tE_T_sistema_s\tdelivery_lag_min\n")
            for r in rows:
                delta = (r["tot"] - rows[0]["tot"]) / rows[0]["tot"] * 100.0
                f.write(f"{r['label']}\t{r['pers']:.2f}\t{r['inv']:.2f}\t{r['tot']:.2f}\t"
                        f"{delta:+.2f}%\t{r['p_oos']:.5f}\t{r['e_t']:.2f}\t{r['lag']:.2f}\n")
        print(f"Tabella: {tab}")

        print(f"\n{'Scenario':<22}{'personale':>11}{'inventario':>12}{'TOTALE':>10}"
              f"{'d.costo':>9}{'P(OOS)':>9}{'E[T](s)':>9}")
        print("-" * 82)
        for r in rows:
            delta = (r["tot"] - rows[0]["tot"]) / rows[0]["tot"] * 100.0
            print(f"{r['label']:<22}{r['pers']:>11.2f}{r['inv']:>12.2f}{r['tot']:>10.2f}"
                  f"{delta:>+8.1f}%{r['p_oos']*100:>8.2f}%{r['e_t']:>9.1f}")


if __name__ == "__main__":
    main()
