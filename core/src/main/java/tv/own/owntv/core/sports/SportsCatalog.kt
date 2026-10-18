package tv.own.owntv.core.sports

import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport

/**
 * The curated list behind Settings → Sports → Sports and leagues: what can be followed, in the order the
 * picker shows it (sports in [Sport] order, leagues as listed here). Every slug was checked against ESPN
 * on 2026-10-16. Competition names are the competitions' own brand names and are not translated.
 *
 * Cricket is one entry, `cricket/all`: ESPN's cricket header carries every series of the day, and a
 * series lasts weeks, so following series by slug would go stale.
 */
object SportsCatalog {

    val leagues: List<League> = listOf(
        // Football (soccer)
        soccer("uefa.champions", "Champions League"),
        soccer("uefa.europa", "Europa League"),
        soccer("uefa.europa.conf", "Conference League"),
        soccer("uefa.nations", "Nations League"),
        soccer("fifa.world", "FIFA World Cup"),
        soccer("fifa.worldq.uefa", "World Cup Qualifying – UEFA"),
        soccer("uefa.euro", "EURO"),
        soccer("eng.1", "Premier League", "GB"),
        soccer("eng.2", "Championship", "GB"),
        soccer("eng.fa", "FA Cup", "GB"),
        soccer("eng.league_cup", "Carabao Cup", "GB"),
        soccer("eng.w.1", "Women's Super League", "GB"),
        soccer("sco.1", "Scottish Premiership", "GB"),
        soccer("ger.1", "Bundesliga", "DE"),
        soccer("ger.2", "2. Bundesliga", "DE"),
        soccer("ger.dfb_pokal", "DFB-Pokal", "DE"),
        soccer("esp.1", "LaLiga", "ES"),
        soccer("esp.copa_del_rey", "Copa del Rey", "ES"),
        soccer("ita.1", "Serie A", "IT"),
        soccer("ita.coppa_italia", "Coppa Italia", "IT"),
        soccer("fra.1", "Ligue 1", "FR"),
        soccer("fra.coupe_de_france", "Coupe de France", "FR"),
        soccer("ned.1", "Eredivisie", "NL"),
        soccer("por.1", "Liga Portugal", "PT"),
        soccer("bel.1", "Pro League", "BE"),
        soccer("aut.1", "Austrian Bundesliga", "AT"),
        soccer("sui.1", "Swiss Super League", "CH"),
        soccer("tur.1", "Süper Lig", "TR"),
        soccer("tur.2", "1. Lig", "TR"),
        soccer("ksa.1", "Saudi Pro League", "SA"),
        soccer("afc.champions", "AFC Champions League Elite"),
        soccer("ind.1", "Indian Super League", "IN"),
        soccer("usa.1", "MLS", "US"),
        soccer("mex.1", "Liga MX", "MX"),
        soccer("bra.1", "Brasileirão", "BR"),
        soccer("arg.1", "Liga Profesional", "AR"),
        soccer("conmebol.libertadores", "Copa Libertadores"),
        // Basketball
        League(Sport.BASKETBALL, "nba", "NBA", country = "US"),
        League(Sport.BASKETBALL, "wnba", "WNBA", country = "US"),
        // glcn: not on ESPN; served by TheSportsDbSource.
        League(Sport.BASKETBALL, TheSportsDbSource.ACB, "Liga ACB", "ACB", "ES"),
        League(Sport.BASKETBALL, "mens-college-basketball", "NCAA Basketball", "NCAAM", "US"),
        // Tennis
        League(Sport.TENNIS, "atp", "ATP"),
        League(Sport.TENNIS, "wta", "WTA"),
        // Cricket
        League(Sport.CRICKET, CRICKET_ALL, "Cricket"),
        // Motorsport
        League(Sport.RACING, "f1", "Formula 1", "F1"),
        // glcn: not on ESPN; served by MotoGpSource.
        League(Sport.RACING, CompositeSportsSource.MOTOGP, "MotoGP"),
        League(Sport.RACING, "irl", "IndyCar", country = "US"),
        League(Sport.RACING, "nascar-premier", "NASCAR Cup Series", "NASCAR", "US"),
        // Ice hockey
        League(Sport.HOCKEY, "nhl", "NHL", country = "US"),
        // American football
        League(Sport.FOOTBALL, "nfl", "NFL", country = "US"),
        League(Sport.FOOTBALL, "college-football", "College Football", "NCAAF", "US"),
        // MMA
        League(Sport.MMA, "ufc", "UFC"),
        // Baseball
        League(Sport.BASEBALL, "mlb", "MLB", country = "US"),
        // Golf
        League(Sport.GOLF, "pga", "PGA Tour"),
        League(Sport.GOLF, "eur", "DP World Tour"),
        League(Sport.GOLF, "lpga", "LPGA Tour"),
        League(Sport.GOLF, "liv", "LIV Golf"),
        // Rugby union — ESPN's rugby slugs are numbers.
        League(Sport.RUGBY, "180659", "Six Nations"),
        League(Sport.RUGBY, "164205", "Rugby World Cup"),
        League(Sport.RUGBY, "244293", "The Rugby Championship"),
        League(Sport.RUGBY, "271937", "Champions Cup"),
        League(Sport.RUGBY, "267979", "Premiership Rugby", country = "GB"),
        League(Sport.RUGBY, "270559", "Top 14", country = "FR"),
        League(Sport.RUGBY, "270557", "United Rugby Championship"),
        League(Sport.RUGBY, "242041", "Super Rugby Pacific"),
        // Rugby league, Aussie rules
        League(Sport.RUGBY_LEAGUE, "3", "NRL", country = "AU"),
        League(Sport.AUSSIE_RULES, "afl", "AFL", country = "AU"),
    )

    private val byKey: Map<String, League> = leagues.associateBy { it.key }

    fun league(key: String): League? = byKey[key]

    /**
     * The names a guide or a channel may call [match]'s competition: the catalogue's brand name
     * ("Bundesliga") and ESPN's own ("German Bundesliga"). A cricket match keeps only its series name:
     * its feed, `cricket/all`, is every series at once.
     */
    fun competitionNames(match: Match): List<String> =
        (listOfNotNull(league(League.keyOf(match.sport, match.leagueSlug))?.name, match.leagueName) +
            ALSO_CALLED[League.keyOf(match.sport, match.leagueSlug)].orEmpty()).distinct()

    /** glcn: other names guides use for a competition (the ACB is "Liga Endesa" on TV). */
    private val ALSO_CALLED = mapOf(
        League.keyOf(Sport.BASKETBALL, TheSportsDbSource.ACB) to listOf("Liga Endesa", "ACB"),
    )

    /** The sports that have something in the catalogue, in chip order. */
    val sports: List<Sport> get() = Sport.entries.filter { s -> leagues.any { it.sport == s } }

    fun leaguesOf(sport: Sport): List<League> = leagues.filter { it.sport == sport }

    private fun soccer(slug: String, name: String, country: String? = null) = League(Sport.SOCCER, slug, name, country = country)

    private const val CRICKET_ALL = "all"
}
