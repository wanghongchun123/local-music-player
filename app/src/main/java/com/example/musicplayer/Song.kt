package com.example.musicplayer

import android.net.Uri

/**
 * 一首歌曲的数据。
 * @param name 显示用的文件名
 * @param uri  该文件的 Uri（通过存储访问框架授权得到）
 * @param path 本地绝对路径（自建浏览界面选中的本地文件）；为 null 时用 uri 播放
 */
data class Song(
    val name: String,
    val uri: Uri,
    val path: String? = null
)
