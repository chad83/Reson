package com.reson.player.data.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;

import java.util.List;

public final class CollectionTables {
    private CollectionTables() {}

    @Entity(tableName = "player_playlists")
    public static class Playlist {
        @NonNull @PrimaryKey public String id = "";
        @NonNull public String title = "";
    }

    public static class SavedTrack {
        @NonNull public String uri = "";
        @NonNull public String source = "";
        @NonNull public String title = "";
        @Nullable public String artist;
        @Nullable public String album;
        @Nullable public String albumArtist;
        @Nullable public Integer trackNumber;
        @Nullable public Integer discNumber;
        public long durationMs;
    }

    @Entity(tableName = "player_playlist_tracks", primaryKeys = {"playlistId", "uri", "source"},
            foreignKeys = @ForeignKey(entity = Playlist.class, parentColumns = "id",
                    childColumns = "playlistId", onDelete = ForeignKey.CASCADE))
    public static class Entry {
        @NonNull public String playlistId = "";
        @NonNull @Embedded public SavedTrack track = new SavedTrack();
        public long position;
    }

    @Entity(tableName = "player_history")
    public static class History {
        @PrimaryKey(autoGenerate = true) public long id;
        public long playedAt;
        @NonNull @Embedded public SavedTrack track = new SavedTrack();
    }

    @Dao
    public interface Access {
        @Query("SELECT * FROM player_playlists ORDER BY title COLLATE NOCASE, id")
        List<Playlist> playlists();

        @Query("SELECT EXISTS(SELECT 1 FROM player_playlists WHERE id = :id)")
        boolean hasPlaylist(String id);

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void insertPlaylist(Playlist playlist);

        @Query("UPDATE player_playlists SET title = :title WHERE id = :id")
        void renamePlaylist(String id, String title);

        @Query("DELETE FROM player_playlists WHERE id = :id")
        void deletePlaylist(String id);

        @Query("SELECT * FROM player_playlist_tracks ORDER BY playlistId, position")
        List<Entry> entries();

        @Query("SELECT * FROM player_playlist_tracks WHERE playlistId = :id ORDER BY position")
        List<Entry> entries(String id);

        @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM player_playlist_tracks WHERE playlistId = :id")
        long nextPosition(String id);

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void insertEntry(Entry entry);

        @Query("UPDATE player_playlist_tracks SET position = :position "
                + "WHERE playlistId = :id AND uri = :uri AND source = :source")
        void moveEntry(String id, String uri, String source, long position);

        @Query("DELETE FROM player_playlist_tracks WHERE playlistId = :id AND uri = :uri AND source = :source")
        int deleteEntry(String id, String uri, String source);

        @Insert
        void insertHistory(History entry);

        @Query("SELECT * FROM player_history ORDER BY id DESC")
        List<History> history();

        @Query("DELETE FROM player_history WHERE id < (SELECT id FROM player_history ORDER BY id DESC LIMIT 1 OFFSET :offset)")
        void trimHistory(int offset);

        @Query("DELETE FROM player_history")
        void clearHistory();
    }
}
