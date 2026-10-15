package com.fantaformula1.app.core.domain

/**
 * Pilota della griglia corrente. `id` e' la scelta stabile usata nei jsonb di
 * pronostici ed esiti: non cambia mai, anche se il pilota cambia scuderia.
 * `raceNumber` e' il numero di gara ufficiale (dati forniti dall'utente);
 * null = numero non disponibile.
 */
data class Driver(
    val id: String,
    val name: String,
    val team: String,
    val raceNumber: Int? = null,
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
        Driver("VER", "Max Verstappen", "Red Bull Racing", raceNumber = 3),
        Driver("HAD", "Isack Hadjar", "Red Bull Racing", raceNumber = 6),
        Driver("LAW", "Liam Lawson", "Racing Bulls", raceNumber = 30),
        Driver("LIN", "Arvid Lindblad", "Racing Bulls", raceNumber = 41),
        Driver("NOR", "Lando Norris", "McLaren", raceNumber = 1),
        Driver("PIA", "Oscar Piastri", "McLaren", raceNumber = 81),
        Driver("LEC", "Charles Leclerc", "Ferrari", raceNumber = 16),
        Driver("HAM", "Lewis Hamilton", "Ferrari", raceNumber = 44),
        Driver("RUS", "George Russell", "Mercedes", raceNumber = 63),
        Driver("ANT", "Kimi Antonelli", "Mercedes", raceNumber = 12),
        Driver("ALO", "Fernando Alonso", "Aston Martin", raceNumber = 14),
        Driver("STR", "Lance Stroll", "Aston Martin", raceNumber = 18),
        Driver("GAS", "Pierre Gasly", "Alpine", raceNumber = 10),
        Driver("COL", "Franco Colapinto", "Alpine", raceNumber = 43),
        Driver("OCO", "Esteban Ocon", "Haas", raceNumber = 31),
        Driver("BEA", "Oliver Bearman", "Haas", raceNumber = 87),
        Driver("ALB", "Alexander Albon", "Williams", raceNumber = 23),
        Driver("SAI", "Carlos Sainz", "Williams", raceNumber = 55),
        Driver("HUL", "Nico Hulkenberg", "Audi", raceNumber = 27),
        Driver("BOR", "Gabriel Bortoleto", "Audi", raceNumber = 5),
        Driver("BOT", "Valtteri Bottas", "Cadillac", raceNumber = 77),
        Driver("PER", "Sergio Perez", "Cadillac", raceNumber = 11),
    )

    /** Piloti non piu' titolari ma presenti nei dati gia' salvati. */
    val legacy: List<Driver> = listOf(
        Driver("TSU", "Yuki Tsunoda", "Red Bull Racing (ex)", raceNumber = null),
    )

    /** Numero massimo di piloti selezionabili (gare complete 2026). */
    const val MAX_DRIVERS: Int = 22

    /** Scuderie del catalogo, ordinate alfabeticamente (per i filtri). */
    val teams: List<String> = all.map { it.team }.distinct().sorted()

    private val byId = (all + legacy).associateBy { it.id }

    fun byId(driverId: String): Driver? = byId[driverId]

    fun nameOf(driverId: String): String = byId[driverId]?.name ?: driverId
}