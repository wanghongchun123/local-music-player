package com.example.musicplayer

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

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

    /**
     * 彻底删除一首歌：物理文件 + 系统媒体库索引一起删。
     *
     * 只调 File.delete() 的话，Android 11+ 的文件管理器（走 MediaStore）里文件仍在，
     * 变成"幽灵文件"。所以有本地路径时，额外按 DATA 列删掉 MediaStore 记录并触发媒体扫描。
     *
     * @return true 表示物理文件删除成功
     */
    fun deleteAudioFile(context: Context, song: Song): Boolean {
        val path = song.path
        var physicalOk = false

        if (path != null) {
            // 有本地绝对路径（自建浏览界面添加）：直接删物理文件
            physicalOk = try {
                File(path).delete()
            } catch (_: Exception) {
                false
            }
            // 同步清理 MediaStore 索引，避免文件管理器显示幽灵文件
            if (physicalOk) {
                removeFromMediaStore(context, path)
            }
        } else {
            // 只有 SAF content uri：走系统删除，会自动同步媒体库
            physicalOk = deleteFile(context, song.uri)
        }

        return physicalOk
    }

    /** 按文件路径删除 MediaStore 中的音频记录，并触发媒体扫描刷新 */
    private fun removeFromMediaStore(context: Context, path: String) {
        try {
            val volumeUri = MediaStore.Audio.Media.getContentUriForPath(path)
                ?: MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.delete(
                volumeUri,
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(path)
            )
        } catch (_: Exception) {
            // MediaStore 删除失败不影响物理文件已删除的结果
        }
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (_: Exception) {
        }
    }
}
