package com.example.musicplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 目录扫描器：遍历用户授权的目录，收集其中所有音频文件。
 */
object DirScanner {

    fun scanDirectories(context: Context, dirUris: List<Uri>): List<Song> {
        val songs = mutableListOf<Song>()
        for (dirUri in dirUris) {
            scanTree(context, dirUri, songs)
        }
        // 按文件名排序，保证顺序播放稳定
        return songs.sortedBy { it.name }
    }

    /**
     * 列出某个目录的直接子项（只一层，不递归）。
     * 返回：子文件夹 + 音频文件。
     */
    fun listDirectEntries(context: Context, dirUri: Uri): List<DirEntry> {
        val doc = DocumentFile.fromTreeUri(context, dirUri) ?: return emptyList()
        val entries = mutableListOf<DirEntry>()
        for (child in doc.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                entries.add(DirEntry(name, child.uri, true))
            } else if (child.isFile && UriHelper.isAudioFile(name)) {
                entries.add(DirEntry(name, child.uri, false))
            }
        }
        // 文件夹在前，歌曲在后；各自按名称排序
        return entries.sortedWith(
            compareBy<DirEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * 判断某目录是否含有子文件夹（只查直接一层）。
     */
    fun hasSubDirectory(context: Context, dirUri: Uri): Boolean {
        val doc = DocumentFile.fromTreeUri(context, dirUri) ?: return false
        for (child in doc.listFiles()) {
            if (child.isDirectory) return true
        }
        return false
    }

    /** 只收集某个目录「直接一层」的音频文件（不含子文件夹里的）。 */
    fun listSongsInDir(context: Context, dirUri: Uri): List<Song> {
        val doc = DocumentFile.fromTreeUri(context, dirUri) ?: return emptyList()
        val songs = mutableListOf<Song>()
        for (child in doc.listFiles()) {
            if (child.isFile) {
                val name = child.name ?: continue
                if (UriHelper.isAudioFile(name)) {
                    songs.add(Song(name, child.uri))
                }
            }
        }
        return songs.sortedBy { it.name }
    }

    private fun scanTree(context: Context, uri: Uri, out: MutableList<Song>) {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return
        val children = doc.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                scanTree(context, child.uri, out)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (UriHelper.isAudioFile(name)) {
                    out.add(Song(name, child.uri))
                }
            }
        }
    }

    // ---------- 本地路径扫描（自建浏览界面，文件已在本地存储） ----------

    /** 列出本地目录的直接子文件夹（按名称排序） */
    fun listLocalSubDirs(dirPath: String): List<File> {
        val dir = File(dirPath)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * 递归收集「叶子目录」（最底层，下面不再有子文件夹的目录）。
     * 若某目录没有子文件夹，则它自己是叶子；否则继续向下钻，忽略中间层。
     */
    fun listLocalLeafDirs(dirPath: String): List<File> {
        val dir = File(dirPath)
        if (!dir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        collectLeaves(dir, result)
        return result.sortedBy { it.name.lowercase() }
    }

    private fun collectLeaves(dir: File, out: MutableList<File>) {
        val subs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        if (subs.isEmpty()) {
            // 没有子文件夹：自己是叶子
            out.add(dir)
        } else {
            for (sub in subs) {
                collectLeaves(sub, out)
            }
        }
    }

    /** 列出本地目录「直接一层」的音频文件（不含子文件夹里的） */
    fun listLocalSongsInDir(dirPath: String): List<Song> {
        val dir = File(dirPath)
        if (!dir.isDirectory) return emptyList()
        val songs = mutableListOf<Song>()
        for (child in dir.listFiles() ?: emptyArray()) {
            if (child.isFile && UriHelper.isAudioFile(child.name)) {
                songs.add(Song(child.name, Uri.fromFile(child), child.absolutePath))
            }
        }
        return songs.sortedBy { it.name.lowercase() }
    }
}
