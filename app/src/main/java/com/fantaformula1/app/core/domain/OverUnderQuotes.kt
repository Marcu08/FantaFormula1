package com.fantaformula1.app.core.domain

/**
 * Tabelle di gioco FIXED per safety car / pit stop / testa a testa: non fanno
 * parte di [Rules] (config utente serializzata in JSON) perche' sono costanti
 * di design — non si salvano, non si condividono e non sono modificabili per
 * lega. Le chiavi Pair non sono serializzabili in JSON, da qui la separazione.
 */
object OverUnderQuotes {

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
     * Quota del testa a testa per LIVELLI di griglia (Top/Mid/Bottom da
     * [DriverPointsEstimates.tierOf]). Vincere contro il livello pari paga
     * 1.2x, il livello sotto 0.6x (facile), la sorpresa 1.8x / 2.5x.
     */
    fun headToHeadFactor(picked: String?, opponent: String?): Double {
        if (picked == null || opponent == null) return 1.0
        val tierDiff = DriverPointsEstimates.tierOf(picked) - DriverPointsEstimates.tierOf(opponent)
        return when {
            tierDiff == 0 -> 1.2                       // stesso livello: sfida vera
            tierDiff == -1 -> 0.8                      // picked nel livello sopra
            tierDiff == -2 -> 0.6                      // due livelli sopra: quasi scontato
            tierDiff == 1 -> 1.8                       // sorpresa di un livello
            else -> 2.5                                // sorpresa di due livelli
        }
    }
}
