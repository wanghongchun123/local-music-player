package com.example.musicplayer

import android.net.Uri

/**
 * 一个已授权的音乐目录。
 * @param name 显示名（目录名）
 * @param uri  目录的 tree Uri（SAF 授权）
 */
data class MusicDir(
    val name: String,
    val uri: Uri
)
