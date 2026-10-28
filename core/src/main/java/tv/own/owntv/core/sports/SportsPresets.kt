package tv.own.owntv.core.sports

/**
 * glcn: the competitions people in a country usually follow, so a new profile starts from its own country
 * instead of a list of 120 to go through (Settings › Sports, or the empty Sports hub). A preset only adds
 * to what is followed; it never takes anything away. Keys are [tv.own.owntv.core.sports.model.League.key]s
 * that are in [SportsCatalog]; a key that is not (a competition dropped later) is simply skipped.
 */
object SportsPresets {

    private val EUROPE = listOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.nations", "soccer/uefa.euro", "soccer/fifa.world")
    private val MOTOR = listOf("racing/f1", "racing/motogp")
    private val TENNIS = listOf("tennis/atp", "tennis/wta")
    private val EHF = listOf("handball/ehfcl")

    private val BY_COUNTRY: Map<String, List<String>> = mapOf(
        "ES" to listOf("soccer/esp.1", "soccer/esp.2", "soccer/esp.copa_del_rey", "soccer/esp.super_cup") + EUROPE +
            listOf("basketball/acb", "basketball/euroleague", "basketball/nba", "handball/asobal") + EHF + MOTOR + TENNIS,
        "GB" to listOf("soccer/eng.1", "soccer/eng.2", "soccer/eng.fa", "soccer/eng.league_cup", "soccer/sco.1") + EUROPE +
            listOf("rugby/180659", "rugby/267979", "cricket/all") + MOTOR.take(1) + TENNIS,
        "IE" to listOf("soccer/eng.1", "rugby/180659", "rugby/270557", "rugby/271937") + EUROPE + MOTOR.take(1),
        "DE" to listOf("soccer/ger.1", "soccer/ger.2", "soccer/ger.dfb_pokal") + EUROPE +
            listOf("handball/hbl", "basketball/euroleague") + EHF + MOTOR + TENNIS,
        "AT" to listOf("soccer/aut.1", "soccer/ger.1") + EUROPE + MOTOR + TENNIS,
        "CH" to listOf("soccer/sui.1") + EUROPE + MOTOR + TENNIS,
        "FR" to listOf("soccer/fra.1", "soccer/fra.2", "soccer/fra.coupe_de_france") + EUROPE +
            listOf("rugby/270559", "rugby/180659", "rugby/271937", "handball/lnh", "basketball/euroleague") + EHF + MOTOR + TENNIS,
        "IT" to listOf("soccer/ita.1", "soccer/ita.2", "soccer/ita.coppa_italia") + EUROPE +
            listOf("basketball/euroleague", "rugby/180659") + MOTOR + TENNIS,
        "PT" to listOf("soccer/por.1", "soccer/por.taca.portugal") + EUROPE + EHF + MOTOR,
        "NL" to listOf("soccer/ned.1", "soccer/ned.cup") + EUROPE + MOTOR + TENNIS,
        "BE" to listOf("soccer/bel.1") + EUROPE + MOTOR + TENNIS,
        "DK" to listOf("soccer/den.1", "handball/hhl") + EHF + EUROPE + MOTOR + TENNIS,
        "NO" to listOf("soccer/nor.1") + EHF + EUROPE + MOTOR,
        "SE" to listOf("soccer/swe.1") + EHF + EUROPE + listOf("hockey/nhl") + MOTOR,
        "HU" to listOf("handball/khl") + EHF + EUROPE + MOTOR + TENNIS,
        "GR" to listOf("soccer/gre.1", "basketball/euroleague") + EUROPE,
        "TR" to listOf("soccer/tur.1", "basketball/euroleague") + EUROPE,
        "US" to listOf("football/nfl", "football/college-football", "basketball/nba", "basketball/wnba", "baseball/mlb",
            "hockey/nhl", "soccer/usa.1", "racing/nascar-premier", "racing/irl", "racing/f1", "mma/ufc", "golf/pga") + TENNIS,
        "CA" to listOf("hockey/nhl", "basketball/nba", "football/nfl", "baseball/mlb", "soccer/usa.1", "racing/f1"),
        "MX" to listOf("soccer/mex.1", "soccer/concacaf.champions", "soccer/fifa.world", "soccer/uefa.champions", "football/nfl", "racing/f1", "mma/ufc"),
        "AR" to listOf("soccer/arg.1", "soccer/conmebol.libertadores", "soccer/conmebol.sudamericana", "soccer/fifa.worldq.conmebol",
            "soccer/fifa.world", "soccer/uefa.champions", "soccer/esp.1", "basketball/nba", "racing/f1") + TENNIS,
        "BR" to listOf("soccer/bra.1", "soccer/bra.copa_do_brazil", "soccer/conmebol.libertadores", "soccer/fifa.worldq.conmebol",
            "soccer/fifa.world", "soccer/uefa.champions", "basketball/nba", "racing/f1", "mma/ufc"),
        "AU" to listOf("australian-football/afl", "rugby-league/3", "rugby/242041", "cricket/all", "soccer/eng.1", "racing/f1", "racing/motogp") + TENNIS,
        "NZ" to listOf("rugby/242041", "rugby/244293", "rugby-league/3", "cricket/all", "racing/f1"),
        "IN" to listOf("cricket/all", "soccer/ind.1", "soccer/eng.1", "racing/f1"),
        "SA" to listOf("soccer/ksa.1", "soccer/afc.champions", "soccer/uefa.champions", "racing/f1"),
    )

    /** The preset's country codes, for a chooser (the device's own country first when it has one). */
    val countries: List<String> get() = BY_COUNTRY.keys.toList()

    /** The competitions for [country] (ISO 3166 alpha-2), as far as they are in the catalogue; empty when there is no preset. */
    fun leaguesFor(country: String?): List<String> {
        val keys = BY_COUNTRY[country?.uppercase()] ?: return emptyList()
        val known = SportsCatalog.leagues.map { it.key }.toSet()
        return keys.distinct().filter { it in known }
    }

    fun hasPreset(country: String?): Boolean = country?.uppercase() in BY_COUNTRY
}
