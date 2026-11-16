package com.reson.player.data.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.ColumnInfo;
import androidx.room.Database;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.ForeignKey;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;

import java.util.List;

@Database(entities = {LibraryDatabase.Entry.class, LibraryDatabase.NasFolder.class,
        LibraryDatabase.Artwork.class, LibraryDatabase.OfflineCopy.class, LibraryDatabase.OfflineTask.class, LibraryDatabase.SyncEntry.class,
        CollectionTables.Playlist.class, CollectionTables.Entry.class, CollectionTables.History.class},
        version = 7, exportSchema = false)
public abstract class LibraryDatabase extends RoomDatabase {
    public abstract IndexDao index();
    public abstract ArtworkDao artwork();
    public abstract OfflineDao offline();
    public abstract CollectionTables.Access collections();

    @Entity(tableName = "library_tracks", primaryKeys = {"uri", "staged"},
            indices = {@Index({"source", "staged"}), @Index("artworkHash")})
    public static class Entry {
        @NonNull @Embedded public TrackInfo track = new TrackInfo();
        public boolean staged;
    }

    public static class TrackInfo {
        @NonNull public String uri = "";
        @NonNull public String source = "";
        @NonNull public String revision = "";
        @NonNull public String title = "";
        @Nullable public String artist;
        @Nullable public String album;
        @Nullable public String albumArtist;
        public long durationMs;
        @ColumnInfo(defaultValue = "0") public long addedAt;
        @Nullable public Integer trackNumber;
        @Nullable public Integer discNumber;
        @Nullable public String artworkHash;
    }

    @Entity(tableName = "library_nas_folders", primaryKeys = {"profileId", "uri"})
    public static class NasFolder {
        @NonNull public String profileId = "";
        @NonNull public String source = "";
        @NonNull public String uri = "";
    }

    @Entity(tableName = "offline_copies", primaryKeys = "id",
            indices = {@Index(value = {"uri", "source"}, unique = true), @Index("artworkHash")})
    public static class OfflineCopy {
        @NonNull @Embedded public OfflineInfo info = new OfflineInfo();
    }

    public static class OfflineInfo {
        @NonNull public String id = "";
        @NonNull @Embedded public TrackInfo track = new TrackInfo();
        @NonNull public String fileName = "";
        public long sizeBytes;
        @ColumnInfo(defaultValue = "0") public boolean manualDownload;
    }

    @Entity(tableName = "offline_tasks",
            indices = @Index(value = {"uri", "source", "folder"}, unique = true))
    public static class OfflineTask {
        @NonNull @PrimaryKey public String id = "";
        @NonNull public String uri = "";
        @NonNull public String source = "";
        @NonNull public String title = "";
        public boolean folder;
        @ColumnInfo(defaultValue = "0") public boolean manualDownload;
        @Nullable public String error;
        public long lastSynced;
        @Nullable public String syncWarning;
    }

    @Entity(tableName = "offline_sync_entries",
            indices = {@Index(value = {"taskId", "uri"}, unique = true), @Index({"taskId", "sequence"})},
            foreignKeys = @ForeignKey(entity = OfflineTask.class, parentColumns = "id",
                    childColumns = "taskId", onDelete = ForeignKey.CASCADE))
    public static class SyncEntry {
        @PrimaryKey(autoGenerate = true) public long sequence;
        @NonNull public String taskId = "";
        @NonNull public String uri = "";
        public boolean folder;
        public long size;
        public long modified;
    }

    @Entity(tableName = "artwork")
    public static class Artwork {
        @NonNull @PrimaryKey public String hash = "";
        @NonNull public byte[] bytes = new byte[0];
    }

    static final String UNUSED_ARTWORK = "NOT EXISTS (SELECT 1 FROM library_tracks WHERE artworkHash = artwork.hash)"
            + " AND NOT EXISTS (SELECT 1 FROM offline_copies WHERE artworkHash = artwork.hash)";

    @Dao
    public interface ArtworkDao {
        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void save(Artwork artwork);

        @Query("DELETE FROM artwork WHERE hash = :hash AND " + UNUSED_ARTWORK)
        void prune(String hash);

        @Query("DELETE FROM artwork WHERE " + UNUSED_ARTWORK)
        void pruneUnused();
    }

    @Dao
    public interface OfflineDao {
        String COPY_INFO = "SELECT id, fileName, sizeBytes, uri, source, revision, title, artist, "
                + "album, albumArtist, durationMs, trackNumber, discNumber, artworkHash, addedAt, manualDownload FROM offline_copies";

        @Query(COPY_INFO + " ORDER BY title COLLATE NOCASE, id")
        List<OfflineInfo> copies();

        @Query(COPY_INFO + " WHERE id = :id")
        @Nullable OfflineInfo copy(String id);

        @Query(COPY_INFO + " WHERE uri = :uri AND source = :source")
        @Nullable OfflineInfo copyFor(String uri, String source);

        @Query("SELECT artwork.bytes FROM offline_copies JOIN artwork ON artworkHash = hash WHERE id = :id AND revision = :revision")
        @Nullable byte[] artwork(String id, String revision);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void saveCopy(OfflineCopy copy);

        @Query("UPDATE offline_copies SET manualDownload = 1 WHERE uri = :uri AND source = :source")
        void protectCopy(String uri, String source);

        @Query("DELETE FROM offline_copies WHERE id = :id")
        void deleteCopy(String id);

        @Query("SELECT * FROM offline_tasks ORDER BY title COLLATE NOCASE, id")
        List<OfflineTask> tasks();

        @Query("SELECT * FROM offline_tasks WHERE id = :id")
        @Nullable OfflineTask task(String id);

        @Query("SELECT * FROM offline_tasks WHERE uri = :uri AND source = :source AND folder = :folder")
        @Nullable OfflineTask taskFor(String uri, String source, boolean folder);

        @Insert
        void saveTask(OfflineTask task);

        @Query("UPDATE offline_tasks SET manualDownload = 1 WHERE id = :id")
        void protectTask(String id);

        @Query("UPDATE offline_tasks SET error = :error WHERE id = :id")
        void setError(String id, @Nullable String error);

        @Query("UPDATE offline_tasks SET lastSynced = :timestamp, error = syncWarning, syncWarning = NULL WHERE id = :id")
        void markSynced(String id, long timestamp);

        @Query("UPDATE offline_tasks SET syncWarning = :warning WHERE id = :id")
        void setSyncWarning(String id, String warning);

        @Query("SELECT * FROM offline_sync_entries WHERE taskId = :taskId ORDER BY sequence LIMIT 1")
        @Nullable SyncEntry nextSyncEntry(String taskId);

        @Insert(onConflict = OnConflictStrategy.IGNORE)
        void addSyncEntries(List<SyncEntry> entries);

        @Query("DELETE FROM offline_sync_entries WHERE taskId = :taskId AND uri = :uri")
        void deleteSyncEntry(String taskId, String uri);

        @Query("DELETE FROM offline_tasks WHERE id = :id")
        void deleteTask(String id);
    }

    @Dao
    public interface IndexDao {
        String TRACK_INFO = "SELECT uri, source, revision, title, artist, album, albumArtist, "
                + "durationMs, trackNumber, discNumber, artworkHash, addedAt FROM library_tracks";

        @Query(TRACK_INFO + " WHERE staged = 0")
        List<TrackInfo> tracks();

        @Query(TRACK_INFO + " WHERE source = :source AND staged = 0")
        List<TrackInfo> tracks(String source);

        @Query("SELECT artwork.bytes FROM library_tracks JOIN artwork ON artworkHash = hash WHERE uri = :uri AND revision = :revision AND staged = 0")
        @Nullable byte[] artwork(String uri, String revision);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void stage(List<Entry> entries);

        @Query("DELETE FROM library_tracks WHERE source = :source AND staged = 1")
        void discardStaged(String source);

        @Query("DELETE FROM library_tracks WHERE source = :source")
        void deleteSource(String source);

        @Query("DELETE FROM library_tracks WHERE uri = :uri AND source = :source AND staged = 0")
        void deleteTrack(String uri, String source);

        @Query("UPDATE OR REPLACE library_tracks SET staged = 0 WHERE source = :source AND staged = 1")
        void publishStaged(String source);

        @Query("SELECT * FROM library_nas_folders")
        List<NasFolder> nasFolders();

        @Query("SELECT * FROM library_nas_folders WHERE profileId = :id")
        List<NasFolder> nasFolders(String id);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void saveNasFolder(NasFolder folder);

        @Query("DELETE FROM library_nas_folders WHERE profileId = :id AND uri = :uri")
        void deleteNasFolder(String id, String uri);
    }
}
