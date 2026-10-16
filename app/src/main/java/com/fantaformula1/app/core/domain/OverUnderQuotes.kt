package com.fantaformula1.app.core.domain

/**
 * Tabelle di gioco FIXED per safety car / pit stop / testa a testa: non fanno
 * parte di [Rules] (config utente serializzata in JSON) perche' sono costanti
 * di design — non si salvano, non si condividono e non sono modificabili per
 * lega. Le chiavi Pair non sono serializzabili in JSON, da qui la separazione.
 */
object OverUnderQuotes {

    // ----------------------------------------------------------------------
    // Quote a VALORE ATTESO costante (2026-10): la quota di ogni scelta e'
    // 0.5 / probabilita' stimata dell'esito, limitata in [0.5; 3.0].
    // Il valore atteso (p * quota) vale cosi' ~0.5 per QUALSIASI scelta: non
    // esiste piu' un lato "gratis" ("non ritira" / "under" su un pilota che
    // non segna mai) — i punti guadagnati compensano esattamente la probabilita'.
    // ----------------------------------------------------------------------

    /** Valore atteso target di ogni lato del pronostico. */
    const val EV_TARGET = 0.5

    /** Limiti di quota generici (coerenti con validazione e trigger SQL). */
    const val QUOTA_MIN = 0.5
    const val QUOTA_MAX = 3.0

    /**
     * Limiti DEDICATI al DNF (0010): il tasso di ritiro e' quasi sempre basso
     * (~0.10), quindi con il tetto generico 3.0 il lato "si" restava
     * permanentemente clampato (EV ~0.3) mentre il "no" pagava libero
     * (EV ~0.5): il "no" dominava. Con il tetto 8.0 entrambi i lati stanno
     * non-clampati per rate >= ~0.0625 e l'EV e' identico su entrambi.
     */
    const val QUOTA_MIN_DNF = 0.5
    const val QUOTA_MAX_DNF = 8.0

    /**
     * Quota per un esito di probabilita' [p]: 0.5 / p, limitata in [0.5; 3.0].
     * p viene limitata in [0; 1] PRIMA della divisione: una probabilita'
     * niente/vuota non deve produrre una quota ridotta (il min) ma il tetto.
     */
    fun quotaFor(p: Double): Double {
        val pSafe = p.coerceIn(0.0, 1.0).coerceAtLeast(0.01)
        return (EV_TARGET / pSafe).coerceIn(QUOTA_MIN, QUOTA_MAX)
    }

    /**
     * Quota per il DNF: stessa formula del generale ma con i limiti dedicati
     * [QUOTA_MIN_DNF; QUOTA_MAX_DNF] (0010). Validazione Kotlin e trigger SQL
     * usano QUESTI limiti per DRIVER_DNF, i generali per gli altri tipi.
     */
    fun quotaDnfFor(p: Double): Double {
        val pSafe = p.coerceIn(0.0, 1.0).coerceAtLeast(0.01)
        return (EV_TARGET / pSafe).coerceIn(QUOTA_MIN_DNF, QUOTA_MAX_DNF)
    }

    /**
     * Quote del pronostico DNF per un pilota con tasso di ritiro [dnfRate]:
     * prima = "ritira", seconda = "non ritira". Con dnfRate clampato <= 0.5
     * (vedi [DriverSeasonStats.dnfRate]) "non ritira" non paga mai piu'
     * di "ritira", e dal tetto DNF 8.0 i due lati hanno lo STESSO valore
     * atteso per quasi tutte le rate reali (0010).
     */
    fun dnfQuotas(dnfRate: Double): Pair<Double, Double> {
        val pSi = dnfRate.coerceAtLeast(0.01)
        val pNo = (1.0 - dnfRate).coerceAtLeast(0.01)
        return quotaDnfFor(pSi) to quotaDnfFor(pNo)
    }

    /**
     * Quote dei "punti sopra/sotto" per un pilota con [overCount] gare oltre la
     * soglia su [n] gare: p_over = (overCount + 1.5) / (n + 3) (smoothing Beta
     * simmetrico). Prima = "sopra", seconda = "sotto".
     */
    fun pointsOverUnderQuotas(overCount: Int, n: Int): Pair<Double, Double> {
        val pOver = (overCount + 1.5) / (n + 3.0)
        return quotaFor(pOver) to quotaFor(1.0 - pOver)
    }

    /**
     * Quota del testa a testa per differenza di FASCIA (0=ALTA, 1=MEDIA,
     * 2=BASSA): stesso livello 1.2x, favorito 0.8x/0.6x (uno/due livelli),
     * sorpresa 1.8x/2.5x. Versione a indici usata con i tier REALI da
     * [DriverSeasonStats.tierOf] (la versione con piloti sotto usa il prior).
     */
    fun headToHeadFactor(pickedTier: Int, opponentTier: Int): Double =
        when (pickedTier - opponentTier) {
            0 -> 1.2                       // stesso livello: sfida vera
            -1 -> 0.8                      // picked nel livello sopra
            -2 -> 0.6                      // due livelli sopra: quasi scontato
            1 -> 1.8                       // sorpresa di un livello
            else -> 2.5                    // sorpresa di due livelli
        }

    /** Opzioni safety car: numero esatto, "6+" codificato come 6..99. */
    val safetyCountBands: List<Pair<Int, Int>> = listOf(
        0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 99,
    )

    /**
     * Safety car: opzioni a numero esatto (SC+VSC contate insieme). Payout per
     * rarita': 0 e 4+ sono rari, 1-2 sono i piu' comuni.
     */
    fun safetyBandFactor(min: Int, max: Int): Double = when (min) {
        0 -> 2.5
        1 -> 0.7
        2 -> 0.8
        3 -> 1.0
        4 -> 1.4
        5 -> 2.2
        else -> 3.0 // 6 o piu'
    }

    /** Fasce obbligatorie pit stop TOTALI della griglia. */
    val pitStopBands: List<Pair<Int, Int>> = listOf(
        0 to 14, 15 to 25, 26 to 35, 36 to 45, 46 to 55, 56 to 65, 66 to 99,
    )

    /**
     * Pit stop TOTALI: payout per rarita' (46-55 e' la fascia tipica e paga
     * meno, gli estremi pagano di piu').
     */
    fun pitBandFactor(min: Int, max: Int): Double = when (min) {
        0 -> 3.0
        15 -> 1.6
        26 -> 0.9
        36 -> 0.7
        46 -> 0.8
        56 -> 1.8
        else -> 3.0 // 66+
    }

    /**
     * Quota del testa a testa per LIVELLI derivati dal PRIOR pre-stagione:
     * [DriverPointsEstimates.tierOf]. Usata dal motore per i pronostici vecchi
     * (quota congelata assente). Il salvataggio nuovo congela i tier reali:
     */
    fun headToHeadFactor(picked: String?, opponent: String?): Double {
        if (picked == null || opponent == null) return 1.0
        return headToHeadFactor(DriverPointsEstimates.tierOf(picked), DriverPointsEstimates.tierOf(opponent))
    }
}
