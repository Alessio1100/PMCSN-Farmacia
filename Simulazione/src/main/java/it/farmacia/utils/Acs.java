package it.farmacia.utils;

/* -------------------------------------------------------------------------
 * This program is based on a one-pass algorithm for the calculation of an
 * array of autocorrelations r[1], r[2], ... r[K].  The key feature of this
 * algorithm is the circular array 'hold' which stores the (K + 1) most
 * recent data points and the associated index 'p' which points to the
 * (rotating) head of the array.
 *
 * Data is read from a text file in the format 1-data-point-per-line (with
 * no blank lines).  Similar to programs UVS and BVS, this program is
 * designed to be used with OS redirection.
 *
 * NOTE: the constant K (maximum lag) MUST be smaller than the # of data
 * points in the text file, n.  Moreover, if the autocorrelations are to be
 * statistically meaningful, K should be MUCH smaller than n.
 *
 * Name              : Acs.java (AutoCorrelation Statistics)
 * Authors           : Steve Park & Dave Geyer
 * Translation by    : Jun Wang
 * Language          : Java
 * Latest Revision   : 6-16-06
 * -------------------------------------------------------------------------
 */

import java.io.*;
import java.text.DecimalFormat;

public class Acs {

  // Risultato strutturato, utile se vuoi usarlo a video / log
  public static class Result {
    public long n;
    public double mean;
    public double stdev;
    public double r1;
    public double threshold;
    public boolean pass;
  }

  /**
   * Controllo Chatfield su lag=1: |r1| < 2/sqrt(n)
   * Il file deve contenere le medie dei batch, 1 per riga.
   */
  public static Result checkChatfield(String fileName) throws IOException {
    final int K = 1;               // massimo lag calcolato
    final int SIZE = K + 1;

    int i = 0;                     // indice datapoint
    int j;                         // indice lag
    int p = 0;                     // puntatore head su buffer circolare
    double x;                      // valore corrente
    double sum = 0.0;              // somma x[i]
    long n;                        // numero di punti (batch)
    double mean;

    double[] hold  = new double[SIZE]; // ultimi K+1 punti
    double[] cosum = new double[SIZE]; // somme x[i]*x[i+j]

    for (j = 0; j < SIZE; j++) cosum[j] = 0.0;

    String line;
    try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {

      // inizializza buffer con i primi K+1 punti
      while (i < SIZE && (line = br.readLine()) != null) {
        x = Double.parseDouble(line);
        sum += x;
        hold[i] = x;
        i++;
      }

      // scansiona il resto
      while ((line = br.readLine()) != null) {
        for (j = 0; j < SIZE; j++)
          cosum[j] += hold[p] * hold[(p + j) % SIZE];
        x = Double.parseDouble(line);
        sum += x;
        hold[p] = x;
        p = (p + 1) % SIZE;
        i++;
      }
    } catch (EOFException ignore) {
    } catch (NumberFormatException ignore) {
    }

    n = i;
    // svuota il buffer circolare
    while (i < n + SIZE) {
      for (j = 0; j < SIZE; j++)
        cosum[j] += hold[p] * hold[(p + j) % SIZE];
      hold[p] = 0.0;
      p = (p + 1) % SIZE;
      i++;
    }

    mean = sum / n;
    for (j = 0; j <= K; j++)
      cosum[j] = (cosum[j] / (n - j)) - (mean * mean);

    double stdev = Math.sqrt(cosum[0]);
    double r1 = cosum[1] / cosum[0];

    double thr = 2.0 / Math.sqrt(n);            // Chatfield
    boolean pass = Math.abs(r1) < thr;

    DecimalFormat f3 = new DecimalFormat("###0.000");

    System.out.println("Chatfield: ........... = "
            + " |r1|=" + f3.format(Math.abs(r1))
            + "  |  2/sqrt(n)=" + f3.format(thr)
            + "  |  n=" + n + "  →  " + (pass ? "PASS" : "FAIL"));

    Result res = new Result();
    res.n = n;
    res.mean = mean;
    res.stdev = stdev;
    res.r1 = r1;
    res.threshold = thr;
    res.pass = pass;
    return res;
  }

  // --- mantengo il tuo metodo originale se ti serve ancora a video ---
  public static void autocorrelation(String fileName) throws IOException {
    final int K = 1;
    final int SIZE = K + 1;

    int i = 0, j, p = 0;
    double x, sum = 0.0;
    long n;
    double mean;
    double[] hold  = new double[SIZE];
    double[] cosum = new double[SIZE];
    for (j = 0; j < SIZE; j++) cosum[j] = 0.0;

    String line;
    BufferedReader ReadThis = new BufferedReader(new FileReader(fileName));
    try {
      while (i < SIZE && (line = ReadThis.readLine()) != null) {
        x = Double.parseDouble(line); sum += x; hold[i] = x; i++;
      }
      while ((line = ReadThis.readLine()) != null) {
        for (j = 0; j < SIZE; j++)
          cosum[j] += hold[p] * hold[(p + j) % SIZE];
        x = Double.parseDouble(line);
        sum += x;
        hold[p] = x;
        p = (p + 1) % SIZE;
        i++;
      }
    } catch (EOFException | NumberFormatException ignore) {}

    n = i;
    while (i < n + SIZE) {
      for (j = 0; j < SIZE; j++)
        cosum[j] += hold[p] * hold[(p + j) % SIZE];
      hold[p] = 0.0;
      p = (p + 1) % SIZE;
      i++;
    }
    mean = sum / n;
    for (j = 0; j <= K; j++)
      cosum[j] = (cosum[j] / (n - j)) - (mean * mean);

//    java.text.DecimalFormat f = new java.text.DecimalFormat("###0.00");
//    java.text.DecimalFormat g = new java.text.DecimalFormat("###0.000");
//    System.out.println("for " + n + " data points");
//    System.out.println("the mean is ... " + f.format(mean));
//    System.out.println("the stdev is .. " + f.format(Math.sqrt(cosum[0])) + "\n");
//    System.out.println("  j (lag)   r[j] (autocorrelation)");
//    for (j = 1; j < SIZE; j++)
//      System.out.println("  " + j + "          " + g.format(cosum[j] / cosum[0]));
  }
}