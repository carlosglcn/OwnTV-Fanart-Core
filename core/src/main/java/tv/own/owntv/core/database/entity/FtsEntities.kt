package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/*
 * External-content FTS4 tables for fast name search across the big lists. Room keeps them in sync
 * with the base tables via generated triggers; `rowid` equals the base row's id, so search joins on
 * `<table>_fts.rowid = <table>.id`. Only the name is indexed to keep the FTS small at 340k+ items.
 *
 * Channels, movies and series use the `unicode61` tokenizer (v56): the default `simple` one folds A–Z
 * only, so a lowercase Cyrillic or Greek query never found a capitalised name. Episodes have no
 * search reading their index and keep the default.
 */

@Entity(tableName = "channels_fts")
@Fts4(contentEntity = ChannelEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class ChannelFtsEntity(val name: String)

@Entity(tableName = "movies_fts")
@Fts4(contentEntity = MovieEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class MovieFtsEntity(val name: String)

@Entity(tableName = "series_fts")
@Fts4(contentEntity = SeriesEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class SeriesFtsEntity(val name: String)

@Entity(tableName = "episodes_fts")
@Fts4(contentEntity = EpisodeEntity::class)
data class EpisodeFtsEntity(val name: String)
