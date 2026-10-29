package com.example.musicplayer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 自建文件夹浏览界面：不用系统文件管理器，直接列本地存储目录。
 * 点文件夹 = 进入下一层；点「选择此文件夹」= 确认选中当前目录并返回。
 * 顶部「常用文件夹」区块：点常用文件夹 = 直接选中返回；
 * 长按下方当前目录里的文件夹 = 收藏/取消收藏为常用文件夹。
 */
class FolderPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT_PATH = "result_path"
        private const val ROOT = "/storage/emulated/0"
        private const val PREFS_NAME = "music_player_prefs"
        private const val KEY_FAV_DIRS = "fav_dirs"
        // 常用文件夹用换行分隔存绝对路径
        private const val FAV_SEP = "\n"
    }

    private lateinit var tvPath: TextView
    private lateinit var tvFavLabel: TextView
    private lateinit var listDirs: ListView
    private lateinit var listFavs: ListView

    private var currentDir: File = File(ROOT)
    private var dirs: List<File> = emptyList()
    private var favs: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        tvPath = findViewById(R.id.tv_picker_path)
        tvFavLabel = findViewById(R.id.tv_fav_label)
        listDirs = findViewById(R.id.list_picker_dirs)
        listFavs = findViewById(R.id.list_picker_favs)

        findViewById<TextView>(R.id.btn_picker_up).setOnClickListener { goUp() }
        findViewById<TextView>(R.id.btn_picker_confirm).setOnClickListener { confirm() }

        // 常用文件夹：点选 = 快速跳转到该目录（进入它，再点「选择此文件夹」确认）
        listFavs.setOnItemClickListener { _, _, position, _ ->
            val path = favs[position]
            val f = File(path)
            if (f.exists() && f.isDirectory) {
                loadDir(f)
            } else {
                Toast.makeText(this, "该常用文件夹已不存在，已移除", Toast.LENGTH_SHORT).show()
                favs.removeAt(position)
                saveFavs()
                refreshFavs()
            }
        }
        // 长按常用文件夹 = 取消收藏
        listFavs.setOnItemLongClickListener { _, _, position, _ ->
            val removed = favs[position]
            favs.removeAt(position)
            saveFavs()
            refreshFavs()
            Toast.makeText(this, "已取消收藏：${File(removed).name}", Toast.LENGTH_SHORT).show()
            true
        }

        // 当前目录文件夹：点 = 进入下一层；长按 = 收藏/取消收藏
        listDirs.setOnItemClickListener { _, _, position, _ ->
            loadDir(dirs[position])
        }
        listDirs.setOnItemLongClickListener { _, _, position, _ ->
            toggleFav(dirs[position])
            true
        }

        favs = loadFavs().toMutableList()
        refreshFavs()
        loadDir(File(ROOT))
    }

    private fun toggleFav(dir: File) {
        val path = dir.absolutePath
        if (favs.contains(path)) {
            favs.remove(path)
            Toast.makeText(this, "已取消收藏：${dir.name}", Toast.LENGTH_SHORT).show()
        } else {
            favs.add(path)
            Toast.makeText(this, "已收藏到常用：${dir.name}", Toast.LENGTH_SHORT).show()
        }
        saveFavs()
        refreshFavs()
    }

    private fun refreshFavs() {
        // 过滤掉已不存在的目录
        favs = favs.filter { File(it).exists() && File(it).isDirectory }.toMutableList()
        val show = favs.isNotEmpty()
        tvFavLabel.visibility = if (show) View.VISIBLE else View.GONE
        listFavs.visibility = if (show) View.VISIBLE else View.GONE
        listFavs.adapter = object : BaseAdapter() {
            override fun getCount(): Int = favs.size
            override fun getItem(position: Int): Any = favs[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view: View = convertView ?: LayoutInflater.from(this@FolderPickerActivity)
                    .inflate(R.layout.item_song, parent, false)
                val title = view.findViewById<TextView>(R.id.tv_item_title)
                title.text = "⭐ " + File(favs[position]).name
                title.setTextColor(0xFF1DB954.toInt())
                return view
            }
        }
    }

    private fun loadFavs(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_FAV_DIRS, "") ?: ""
        return str.split(FAV_SEP).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveFavs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FAV_DIRS, favs.joinToString(FAV_SEP)).apply()
    }

    private fun loadDir(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "无法访问该目录", Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = dir
        tvPath.text = dir.absolutePath
        dirs = DirScanner.listLocalSubDirs(dir.absolutePath)
        listDirs.adapter = object : BaseAdapter() {
            override fun getCount(): Int = dirs.size
            override fun getItem(position: Int): Any = dirs[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view: View = convertView ?: LayoutInflater.from(this@FolderPickerActivity)
                    .inflate(R.layout.item_song, parent, false)
                val title = view.findViewById<TextView>(R.id.tv_item_title)
                val name = dirs[position].name
                title.text = if (favs.contains(dirs[position].absolutePath)) "⭐ $name" else "📁 $name"
                title.setTextColor(0xFFFFFFFF.toInt())
                return view
            }
        }
    }

    private fun goUp() {
        val parent = currentDir.parentFile
        if (parent != null && parent.exists()) {
            loadDir(parent)
        } else {
            Toast.makeText(this, "已到最顶层", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirm() {
        returnPath(currentDir.absolutePath)
    }

    private fun returnPath(path: String) {
        val result = android.content.Intent()
        result.putExtra(EXTRA_RESULT_PATH, path)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun onBackPressed() {
        val parent = currentDir.parentFile
        if (parent != null && parent.exists() && parent.absolutePath != currentDir.absolutePath) {
            loadDir(parent)
        } else {
            super.onBackPressed()
        }
    }
}
