package com.opentube.tubedw.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base de datos local (almacenamiento privado de la app) para canales, "me gusta"
 * y el historial de videos vistos.
 */
class AppDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE channels (" +
                "id TEXT PRIMARY KEY, name TEXT, url TEXT, thumbnail TEXT, added_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE likes (" +
                "id TEXT PRIMARY KEY, title TEXT, thumbnail TEXT, url TEXT, " +
                "uploader TEXT, added_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE history (" +
                "id TEXT PRIMARY KEY, title TEXT, thumbnail TEXT, url TEXT, " +
                "uploader TEXT, watched_at INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS channels")
        db.execSQL("DROP TABLE IF EXISTS likes")
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    /* ------------------------------------------------------------------ */
    /* Canales                                                            */
    /* ------------------------------------------------------------------ */

    fun saveChannel(channel: ChannelInfo) {
        val values = ContentValues().apply {
            put("id", channel.id)
            put("name", channel.name)
            put("url", channel.url)
            put("thumbnail", channel.thumbnail)
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "channels", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeChannel(id: String) {
        writableDatabase.delete("channels", "id=?", arrayOf(id))
    }

    fun isChannelSaved(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM channels WHERE id=?", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    fun getChannels(): List<ChannelInfo> {
        val list = mutableListOf<ChannelInfo>()
        readableDatabase.rawQuery("SELECT * FROM channels ORDER BY added_at DESC", null).use { c ->
            val idIdx = c.getColumnIndexOrThrow("id")
            val nameIdx = c.getColumnIndexOrThrow("name")
            val urlIdx = c.getColumnIndexOrThrow("url")
            val thumbIdx = c.getColumnIndexOrThrow("thumbnail")
            while (c.moveToNext()) {
                list.add(
                    ChannelInfo(
                        id = c.getString(idIdx),
                        name = c.getString(nameIdx) ?: "",
                        thumbnail = c.getString(thumbIdx),
                        url = c.getString(urlIdx) ?: ""
                    )
                )
            }
        }
        return list
    }

    /* ------------------------------------------------------------------ */
    /* Me gusta                                                           */
    /* ------------------------------------------------------------------ */

    fun saveLike(item: VideoItem) {
        val values = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("thumbnail", item.thumbnail)
            put("url", item.url)
            put("uploader", item.uploader)
            put("added_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "likes", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeLike(id: String) {
        writableDatabase.delete("likes", "id=?", arrayOf(id))
    }

    fun isLiked(id: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM likes WHERE id=?", arrayOf(id)).use {
            return it.moveToFirst()
        }
    }

    fun getLikes(): List<VideoItem> = queryVideos("likes", "added_at")

    /* ------------------------------------------------------------------ */
    /* Historial                                                          */
    /* ------------------------------------------------------------------ */

    fun saveHistory(item: VideoItem) {
        val values = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("thumbnail", item.thumbnail)
            put("url", item.url)
            put("uploader", item.uploader)
            put("watched_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "history", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getHistory(): List<VideoItem> = queryVideos("history", "watched_at")

    /* ------------------------------------------------------------------ */

    private fun queryVideos(table: String, orderColumn: String): List<VideoItem> {
        val list = mutableListOf<VideoItem>()
        readableDatabase.rawQuery("SELECT * FROM $table ORDER BY $orderColumn DESC", null)
            .use { c ->
                val idIdx = c.getColumnIndexOrThrow("id")
                val titleIdx = c.getColumnIndexOrThrow("title")
                val thumbIdx = c.getColumnIndexOrThrow("thumbnail")
                val urlIdx = c.getColumnIndexOrThrow("url")
                val uploaderIdx = c.getColumnIndexOrThrow("uploader")
                while (c.moveToNext()) {
                    list.add(
                        VideoItem(
                            id = c.getString(idIdx),
                            title = c.getString(titleIdx) ?: "",
                            thumbnail = c.getString(thumbIdx),
                            uploader = c.getString(uploaderIdx),
                            uploaderUrl = null,
                            duration = -1L,
                            viewCount = -1L,
                            url = c.getString(urlIdx) ?: ""
                        )
                    )
                }
            }
        return list
    }

    companion object {
        private const val DB_NAME = "tubedw.db"
        private const val DB_VERSION = 1
    }
}
