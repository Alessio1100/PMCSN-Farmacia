Test di sensitività sulla distribuzione di servizio delle CASSE (vedi CLAUDE.md §22.6).

Domanda: l'esponenziale ha moda in 0 (poco realistico per un servizio con durata minima).
Quanto incide sul sistema usare invece una distribuzione a moda>0 e stessa media?

Orizzonte INFINITO, lambda=0.0190975, 64 batch x 1024 job. Unica differenza = servizio casse:
  exp/      -Dcasse.dist=exp       esponenziale, CV=1, moda 0   (M/M/m, default)
  erlang4/  -Dcasse.dist=erlang4   Erlang-4, CV=0.5, moda>0, STESSA media  (erlang(4, media/4))

Comando (una per sottocartella):
  java -Xmx4g -Dcasse.dist=<exp|erlang4> -Dstats.dir=stats/sensitivity_service_dist/<exp|erlang4> \
       -cp build_cmp it.farmacia.control.InfiniteHorizonSimulation

Esito (media +- IC95% su 64 batch):
  Casse rho              : 0.468 vs 0.468   (invariata -> U=X*E[S] distribution-free: verifica)
  Casse E[Nq] / Wq       : 0.095/5.23s vs 0.071/3.94s   (-24.7% : effetto Kingman, CV piu basso)
  Casse risposta nodo    : 134.7s vs 133.3s  (-1.0%)
  SISTEMA risposta       : 357.8s vs 354.8s  (-0.8% : irrilevante, casse non sono il collo di bottiglia)

Conclusione: la critica (exp sovrastima la coda) e' reale (~25% sulla coda casse) ma IRRILEVANTE
sul KPI di sistema (<1%), perche' le casse sono poco cariche e non sono il bottleneck. Si tiene
l'esponenziale (validabile M/M/m + conservativo). Ai bracci si usa gia' la normale troncata (moda>0).
