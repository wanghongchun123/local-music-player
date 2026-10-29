package com.example.musicplayer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * 把通过存储访问框架得到的 Uri 转成可直接播放的 Uri。
 *
 * 部分系统（尤其国产 ROM）对 document Uri 直接播放支持不好，
 * 这里尝试把 content Uri 转成 file:// 真实路径；转不了就保留原 Uri。
 */
object UriHelper {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus", "mid", "amr"
    )

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: ""
        try {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = it.getString(idx)
                        if (!n.isNullOrBlank()) name = n
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略，用 fallback 名称
        }
        if (name.isBlank()) name = "未知歌曲"
        return name
    }

    /**
     * 删除某个文件（彻底删除本地源文件）。
     * 优先用 DocumentsContract.deleteDocument（需要 SAF 写权限），
     * 失败则尝试 DocumentFile.delete()。
     * @return true 表示删除成功
     */
    fun deleteFile(context: Context, uri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            try {
                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                    ?.delete() == true
            } catch (_: Exception) {
                false
            }
        }
    }
}
