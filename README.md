# Farmacia Automatizzata Gollmann — Modello di Simulazione

Progetto per il corso di **Performance Modeling of Computer Systems and Networks**
(Prof.ssa V. de Nitto Personè) — Università degli Studi di Roma "Tor Vergata", A.A. 2025/2026.

Autore: **Alessio Torroni** — matricola 0365661.

## Descrizione

Lo studio modella, tramite **simulazione a eventi discreti**, una farmacia dotata di sistema
robotizzato di dispensazione **Gollmann**. Il sistema è rappresentato come una **rete di code**
con arrivi non stazionari (NSPP) integrata con un **sottosistema di inventario a revisione
periodica** con politica `(s, S)` su cinque classi di farmaco.

L'obiettivo è la **riduzione dei costi di esercizio a parità di qualità del servizio**, agendo su
due leve operative — la **turnazione del personale** per fascia oraria e la **politica di riordino**
delle scorte — sotto il vincolo che le perdite per *out-of-stock* restino sotto il **5%**.
