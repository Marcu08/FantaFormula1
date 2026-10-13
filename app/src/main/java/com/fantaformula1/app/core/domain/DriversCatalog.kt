package com.fantaformula1.app.core.domain

/**
 * Pilota della griglia corrente. `id` e' la scelta stabile usata nei jsonb di
 * pronostici ed esiti: non cambia mai, anche se il pilota cambia scuderia.
 */
data class Driver(
    val id: String,
    val name: String,
    val team: String,
)

/**
 * Catalogo piloti della stagione. Usato dai form admin (risultati) e dai
 * giocatori (pronostici): evita testo libero e id inventati a runtime.
 *
 * La lista principale segue la griglia 2026. Gli id sono invariati rispetto
 * ai dati gia' salvati: `TSU` resta riconosciuto tramite [legacy] anche se non
 * e' piu' titolare (B16).
 */
object DriversCatalog {

    val all: List<Driver> = listOf(
        Driver("VER", "Max Verstappen", "Red Bull Racing"),
        Driver("HAD", "Isack Hadjar", "Red Bull Racing"),
        Driver("LAW", "Liam Lawson", "Racing Bulls"),
        Driver("LIN", "Arvid Lindblad", "Racing Bulls"),
        Driver("NOR", "Lando Norris", "McLaren"),
        Driver("PIA", "Oscar Piastri", "McLaren"),
        Driver("LEC", "Charles Leclerc", "Ferrari"),
        Driver("HAM", "Lewis Hamilton", "Ferrari"),
        Driver("RUS", "George Russell", "Mercedes"),
        Driver("ANT", "Kimi Antonelli", "Mercedes"),
        Driver("ALO", "Fernando Alonso", "Aston Martin"),
        Driver("STR", "Lance Stroll", "Aston Martin"),
        Driver("GAS", "Pierre Gasly", "Alpine"),
        Driver("COL", "Franco Colapinto", "Alpine"),
        Driver("OCO", "Esteban Ocon", "Haas"),
        Driver("BEA", "Oliver Bearman", "Haas"),
        Driver("ALB", "Alexander Albon", "Williams"),
        Driver("SAI", "Carlos Sainz", "Williams"),
        Driver("HUL", "Nico Hulkenberg", "Audi"),
        Driver("BOR", "Gabriel Bortoleto", "Audi"),
        Driver("BOT", "Valtteri Bottas", "Cadillac"),
        Driver("PER", "Sergio Perez", "Cadillac"),
    )

    /** Piloti non piu' titolari ma presenti nei dati gia' salvati. */
    val legacy: List<Driver> = listOf(
        Driver("TSU", "Yuki Tsunoda", "Red Bull Racing (ex)"),
    )

    /** Numero massimo di piloti selezionabili (gare complete 2026). */
    const val MAX_DRIVERS: Int = 22

    private val byId = (all + legacy).associateBy { it.id }

    fun byId(driverId: String): Driver? = byId[driverId]

    fun nameOf(driverId: String): String = byId[driverId]?.name ?: driverId
}
