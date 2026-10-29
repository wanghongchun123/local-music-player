package com.example.musicplayer

import android.net.Uri

/**
 * 目录浏览时的一项：可能是子文件夹，也可能是歌曲。
 */
data class DirEntry(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean
)
