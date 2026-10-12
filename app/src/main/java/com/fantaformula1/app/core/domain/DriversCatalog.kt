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
 */
object DriversCatalog {

    val all: List<Driver> = listOf(
        Driver("VER", "Max Verstappen", "Red Bull Racing"),
        Driver("TSU", "Yuki Tsunoda", "Red Bull Racing"),
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
        Driver("LAW", "Liam Lawson", "Racing Bulls"),
        Driver("LIN", "Arvid Lindblad", "Racing Bulls"),
        Driver("ALB", "Alexander Albon", "Williams"),
        Driver("SAI", "Carlos Sainz", "Williams"),
        Driver("HUL", "Nico Hulkenberg", "Sauber"),
        Driver("BOR", "Gabriel Bortoleto", "Sauber"),
    )

    private val byId = all.associateBy { it.id }

    fun byId(driverId: String): Driver? = byId[driverId]

    fun nameOf(driverId: String): String = byId[driverId]?.name ?: driverId
}
