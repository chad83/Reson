package com.reson.player.data.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

internal fun artworkRecord(bytes: ByteArray) = LibraryDatabase.Artwork().apply {
    hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    this.bytes = bytes
}

internal val ARTWORK_MIGRATION = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE artwork (hash TEXT NOT NULL PRIMARY KEY, bytes BLOB NOT NULL)")
        migrateArtwork(db, "library_tracks", "staged INTEGER NOT NULL", "staged", "uri, staged")
        migrateArtwork(db, "offline_copies", "id TEXT NOT NULL, fileName TEXT NOT NULL, sizeBytes INTEGER NOT NULL",
            "id, fileName, sizeBytes", "id")
        db.execSQL("CREATE INDEX index_library_tracks_source_staged ON library_tracks(source, staged)")
        db.execSQL("CREATE UNIQUE INDEX index_offline_copies_uri_source ON offline_copies(uri, source)")
    }
}

private fun migrateArtwork(
    db: SupportSQLiteDatabase,
    table: String,
    extraSchema: String,
    extraColumns: String,
    primaryKey: String,
) {
    db.execSQL("""
        CREATE TABLE ${table}_new (
            $extraSchema, uri TEXT NOT NULL, source TEXT NOT NULL, revision TEXT NOT NULL,
            title TEXT NOT NULL, artist TEXT, album TEXT, albumArtist TEXT, durationMs INTEGER NOT NULL,
            trackNumber INTEGER, discNumber INTEGER, artworkHash TEXT, PRIMARY KEY ($primaryKey)
        )
    """.trimIndent())
    val columns = "$extraColumns, uri, source, revision, title, artist, album, albumArtist, durationMs, trackNumber, discNumber"
    db.execSQL("INSERT INTO ${table}_new (rowid, $columns) SELECT rowid, $columns FROM $table")
    db.compileStatement("INSERT OR IGNORE INTO artwork (hash, bytes) VALUES (?, ?)").use { insert ->
        db.compileStatement("UPDATE ${table}_new SET artworkHash = ? WHERE rowid = ?").use { update ->
            db.query("SELECT rowid, artwork FROM $table WHERE artwork IS NOT NULL").use { rows ->
                while (rows.moveToNext()) {
                    val thumbnail = artworkRecord(rows.getBlob(1))
                    insert.bindString(1, thumbnail.hash)
                    insert.bindBlob(2, thumbnail.bytes)
                    insert.executeInsert()
                    update.bindString(1, thumbnail.hash)
                    update.bindLong(2, rows.getLong(0))
                    update.executeUpdateDelete()
                }
            }
        }
    }
    db.execSQL("DROP TABLE $table")
    db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
    db.execSQL("CREATE INDEX index_${table}_artworkHash ON $table(artworkHash)")
}
