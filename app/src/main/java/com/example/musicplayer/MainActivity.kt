package com.example.musicplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_ADD_DIR = 1001
        private const val REQUEST_MANAGE_STORAGE = 1002
        private const val PREFS_NAME = "music_player_prefs"
        private const val KEY_DIR_URIS = "dir_uris"
        private const val KEY_ACTIVE_DIR = "active_dir_uri"
        private const val CACHE_DIR = "song_cache"
        // 记忆播放：上次播放的歌曲 Uri 和进度（全局，用于重启恢复）
        private const val KEY_LAST_SONG_URI = "last_song_uri"
        private const val KEY_LAST_POSITION = "last_position"
        // 按目录记忆播放位置：key 为 "pos_<目录uri的hash>"，value 为 "songUri|position"
        private const val KEY_POS_PREFIX = "pos_"
    }

    private lateinit var tvSongTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnDir: TextView
    private lateinit var btnSleep: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var listSongs: ListView

    private var songs: List<Song> = emptyList()
    private var playbackService: PlaybackService? = null
    private var serviceBound = false
    private var isUserSeeking = false
    private var activeDirUri: Uri? = null
    // 是否需要恢复上次播放位置
    private var pendingResume = false
    // 扫描进度对话框
    private var scanDialog: android.app.ProgressDialog? = null
    // 扫描请求序号，用于丢弃过期的异步结果，避免并发竞态导致列表错乱
    private var scanRequestSeq = 0

    // 区间删除状态
    private var rangeDeleteMode = false
    private var rangeStartIndex = -1

    private val handler = Handler(Looper.getMainLooper())

    // 睡眠倒计时
    private var timerRunning = false
    private var timerRemainingMs = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            timerRemainingMs -= 1000
            if (timerRemainingMs <= 0) {
                timerRunning = false
                btnSleep.text = "睡眠"
                shutdownApp()
            } else {
                btnSleep.text = "睡眠 " + formatRemain(timerRemainingMs)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val svc = playbackService
            if (svc != null && svc.isPlaying() && !isUserSeeking) {
                val pos = svc.currentPosition()
                val dur = svc.duration()
                if (dur > 0) {
                    seekBar.max = dur
                    seekBar.progress = pos
                    tvCurrentTime.text = formatTime(pos.toLong())
                    tvTotalTime.text = formatTime(dur.toLong())
                    // 定期保存播放进度，用于下次启动恢复
                    saveLastPosition(pos)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            serviceBound = true

            playbackService?.onSongChanged = { song ->
                runOnUiThread {
                    tvSongTitle.text = song?.name ?: "未选择歌曲"
                    // 记录当前播放的歌曲，用于下次启动恢复
                    if (song != null) {
                        saveLastSong(song.uri.toString())
                    }
                }
            }
            playbackService?.onPlayStateChanged = { playing ->
                runOnUiThread {
                    btnPlayPause.text = if (playing) "⏸" else "▶"
                }
            }

            playbackService?.setPlaylist(songs)
            val song = playbackService?.currentSong()
            if (song != null) {
                tvSongTitle.text = song.name
                btnPlayPause.text = if (playbackService?.isPlaying() == true) "⏸" else "▶"
                val dur = playbackService?.duration() ?: 0
                seekBar.max = dur
                seekBar.progress = playbackService?.currentPosition() ?: 0
                tvTotalTime.text = formatTime(dur.toLong())
                tvCurrentTime.text = formatTime((playbackService?.currentPosition() ?: 0).toLong())
            }
            // 服务连上后尝试恢复上次播放位置
            tryResumeLastPlayback()
            handler.post(progressTick)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 锁定深色界面：禁止跟随系统深色/浅色设置切换，保证重启后始终是黑色界面
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()

        // 启动时启用「记忆播放位置」恢复
        pendingResume = true

        // 目录扫描放到后台线程，避免阻塞主线程
        autoLoadDirectoriesAsync()

        // 服务绑定延后到界面渲染完成后，避免启动 ANR
        handler.post {
            val intent = Intent(this, PlaybackService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun bindViews() {
        tvSongTitle = findViewById(R.id.tv_song_title)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalTime = findViewById(R.id.tv_total_time)
        btnDir = findViewById(R.id.btn_dir)
        btnSleep = findViewById(R.id.btn_sleep)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        listSongs = findViewById(R.id.list_songs)
    }

    private fun setupListeners() {
        btnDir.setOnClickListener {
            if (rangeDeleteMode) {
                // 区间删除模式下，点目录按钮退出模式
                resetRangeDelete()
            } else {
                showDirManager()
            }
        }
        btnSleep.setOnClickListener { showSleepDialog() }

        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnPrev.setOnClickListener { playbackService?.playPrevious() }
        btnNext.setOnClickListener { playbackService?.playNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                playbackService?.seekTo(seekBar.progress)
                isUserSeeking = false
            }
        })

        listSongs.setOnItemClickListener { _, _, position, _ ->
            if (rangeDeleteMode) {
                onRangeDeleteTap(position)
            } else {
                playAt(position)
            }
        }

        // 长按进入区间删除模式
        listSongs.setOnItemLongClickListener { _, _, position, _ ->
            if (!rangeDeleteMode) {
                enterRangeDeleteMode()
            }
            // 长按也作为一次点选（标记起点/终点）
            onRangeDeleteTap(position)
            true
        }
    }

    // ---------- 目录 ----------

    /**
     * 弹目录管理列表：展示已添加目录，可切换、添加、删除。
     */
    private fun showDirManager() {
        val dirs = getSavedDirs()
        val items = mutableListOf<String>()
        // 当前激活目录加个标记
        val active = activeDirUri?.toString()
        for (d in dirs) {
            items.add(if (d.uri.toString() == active) "▶ ${d.name}" else d.name)
        }
        if (items.isEmpty()) {
            items.add("（还没有目录，点下方添加）")
        }

        AlertDialog.Builder(this)
            .setTitle("目录管理")
            .setItems(items.toTypedArray()) { _, which ->
                if (dirs.isEmpty()) {
                    // 空提示项被点击，直接打开选择器
                    openDirectoryPicker()
                    return@setItems
                }
                val dir = dirs[which]
                // 进入目录树浏览
                enterDirectory(dir.uri, dir.name)
            }
            .setPositiveButton("添加目录") { _, _ -> openDirectoryPicker() }
            .setNeutralButton("删除目录") { _, _ -> showDeleteDirDialog(dirs) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 进入某个目录：如果有子文件夹就显示子文件夹（继续往下钻），
     * 没有子文件夹就列出该层的歌曲并播放。
     * 优先读目录结构缓存，无缓存才扫描。
     */
    private fun enterDirectory(dirUri: Uri, title: String) {
        // 本地目录：直接加载这一层的歌（自建界面已选到具体目录，不再递归子文件夹）
        if (isLocalUri(dirUri)) {
            setActiveDir(dirUri)
            loadDirSongs(dirUri)
            return
        }

        // 任何进入目录的操作都让旧的未完成扫描结果失效
        val seq = ++scanRequestSeq

        // 先读目录结构缓存
        val cached = loadDirCache(dirUri)
        if (cached != null) {
            val (hasSub, subDirs) = cached
            if (hasSub) {
                showSubDirsDialog(subDirs, dirUri, title)
            } else {
                setActiveDir(dirUri)
                // 无子文件夹：加载这一层的歌（有缓存则秒开）
                loadDirSongs(dirUri)
            }
            return
        }

        // 无缓存：扫描目录结构，存缓存
        showScanProgress("正在读取 $title ...")
        Thread {
            val hasSub = DirScanner.hasSubDirectory(this, dirUri)
            val subDirs = if (hasSub) {
                DirScanner.listDirectEntries(this, dirUri).filter { it.isDirectory }
            } else {
                emptyList()
            }
            // 保存目录结构缓存
            saveDirCache(dirUri, hasSub, subDirs)

            // 若没有子文件夹，顺带在后台扫描这一层的歌并存缓存
            val songsHere = if (!hasSub) {
                DirScanner.listSongsInDir(this, dirUri).also {
                    saveSongCache(dirUri, it)
                }
            } else {
                emptyList()
            }

            runOnUiThread {
                // 若期间又发起了新的扫描，丢弃本次结果
                if (seq != scanRequestSeq) return@runOnUiThread
                hideScanProgress()
                if (hasSub) {
                    showSubDirsDialog(subDirs, dirUri, title)
                } else {
                    setActiveDir(dirUri)
                    playThisDir(dirUri, songsHere, title)
                }
            }
        }.start()
    }

    /** 显示子文件夹列表弹窗 */
    private fun showSubDirsDialog(subDirs: List<DirEntry>, parentUri: Uri, parentTitle: String) {
        if (subDirs.isEmpty()) {
            // 理论上不会到这里（hasSub 为 true 但没列出），兜底
            val songsHere = DirScanner.listSongsInDir(this, parentUri)
            setActiveDir(parentUri)
            playThisDir(parentUri, songsHere, parentTitle)
            return
        }
        val names = subDirs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📁 $parentTitle")
            .setItems(names) { _, which ->
                val sub = subDirs[which]
                enterDirectory(sub.uri, sub.name)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    /** 播放某个目录（只这一层）的歌 */
    private fun playThisDir(dirUri: Uri, songsHere: List<Song>, title: String) {
        // 保存缓存，下次秒开
        saveSongCache(dirUri, songsHere)
        playbackService?.clearAndStop()
        songs = songsHere
        updateSongList()
        playbackService?.setPlaylist(songs)
        if (songs.isNotEmpty()) {
            // 尝试恢复该目录上次的位置；无记忆则显示第一首
            val mem = loadDirPosition(dirUri)
            if (mem != null) {
                tryResumeDirPosition(dirUri)
            } else {
                tvSongTitle.text = songs[0].name
            }
            Toast.makeText(this, "已加载：$title（${songs.size} 首）", Toast.LENGTH_SHORT).show()
        } else {
            tvSongTitle.text = "（此目录没有音乐）"
            Toast.makeText(this, "$title 下没有音乐文件", Toast.LENGTH_SHORT).show()
        }
    }

    /** 删除目录弹窗：勾选多选 + 全选 */
    private fun showDeleteDirDialog(dirs: List<MusicDir>) {
        if (dirs.isEmpty()) {
            Toast.makeText(this, "没有可删除的目录", Toast.LENGTH_SHORT).show()
            return
        }
        val names = dirs.map { it.name }.toTypedArray()
        val checked = BooleanArray(dirs.size)
        val dialog = AlertDialog.Builder(this)
            .setTitle("勾选要删除的目录")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("删除") { _, _ ->
                val selected = dirs.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "未勾选任何目录", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                for (dir in selected) {
                    removeDir(dir.uri)
                }
                // 删除后刷新：若激活目录被删则清空列表，否则重新加载
                if (activeDirUri == null) {
                    songs = emptyList()
                    updateSongList()
                    playbackService?.setPlaylist(songs)
                    playbackService?.clearAndStop()
                    tvSongTitle.text = "未选择歌曲"
                } else {
                    rescanSongsAsync()
                }
                Toast.makeText(this, "已删除 ${selected.size} 个目录", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全选", null)
            .show()

        // 全选按钮：点击后切换全选/取消全选，并同步更新列表勾选状态
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val target = !checked.all { it }
            for (i in checked.indices) {
                checked[i] = target
                dialog.listView?.setItemChecked(i, target)
            }
        }
    }

    private fun openDirectoryPicker() {
        // 检查「所有文件访问」权限；没有则跳系统设置开启
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请先开启「所有文件访问」权限", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                }
                return
            }
        }
        // 启动自建文件夹浏览界面
        val intent = Intent(this, FolderPickerActivity::class.java)
        startActivityForResult(intent, REQUEST_ADD_DIR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 从权限设置返回后，若已授权则直接打开浏览界面
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                val intent = Intent(this, FolderPickerActivity::class.java)
                startActivityForResult(intent, REQUEST_ADD_DIR)
            }
            return
        }
        if (requestCode == REQUEST_ADD_DIR && resultCode == Activity.RESULT_OK) {
            val path = data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_PATH) ?: return
            importLocalDir(path)
        }
    }

    /** 导入一个本地父目录：把它的所有「叶子目录」（最底层子文件夹）添加为目录 */
    private fun importLocalDir(path: String) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val name = dir.name.ifBlank { path }
        val dirUri = Uri.fromFile(dir)
        showScanProgress("正在读取 $name ...")
        Thread {
            // 递归收集最底层的叶子目录，跳过中间层
            val leafDirs = DirScanner.listLocalLeafDirs(path)
            runOnUiThread {
                hideScanProgress()
                if (leafDirs.isNotEmpty()) {
                    for (leaf in leafDirs) {
                        val leafUri = Uri.fromFile(leaf)
                        addDir(MusicDir(leaf.name, leafUri))
                    }
                    Toast.makeText(this, "已添加 ${leafDirs.size} 个子文件夹", Toast.LENGTH_SHORT).show()
                } else {
                    // 没有任何子文件夹：添加父目录本身
                    addDir(MusicDir(name, dirUri))
                    Toast.makeText(this, "已导入：$name", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 播放本地目录这一层的歌 */
    private fun playThisLocalDir(dirUri: Uri, songsHere: List<Song>, title: String) {
        playbackService?.clearAndStop()
        songs = songsHere
        updateSongList()
        playbackService?.setPlaylist(songs)
        setActiveDir(dirUri)
        saveSongCache(dirUri, songsHere)
        if (songs.isNotEmpty()) {
            val mem = loadDirPosition(dirUri)
            if (mem != null) {
                tryResumeDirPosition(dirUri)
            } else {
                tvSongTitle.text = songs[0].name
            }
            Toast.makeText(this, "已加载：$title（${songs.size} 首）", Toast.LENGTH_SHORT).show()
        } else {
            tvSongTitle.text = "（此目录没有音乐）"
            Toast.makeText(this, "$title 下没有音乐文件", Toast.LENGTH_SHORT).show()
        }
    }

    /** 判断 uri 是否是本地文件路径（file:// 开头） */
    private fun isLocalUri(uri: Uri): Boolean = uri.scheme == "file"

    /** 从 uri 取目录显示名：file uri 用文件名，content uri 用 DocumentFile.name */
    private fun dirDisplayName(uri: Uri): String {
        if (isLocalUri(uri)) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val name = File(path).name
                if (name.isNotBlank()) return name
            }
        }
        try {
            val name = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(this, uri)?.name
            if (!name.isNullOrBlank()) return name
        } catch (_: Exception) {
        }
        val seg = uri.lastPathSegment ?: ""
        // tree Uri 最后一段形如 "primary:Music"，取冒号后部分
        val afterColon = seg.substringAfterLast(':', seg)
        return afterColon.ifBlank { "音乐目录" }
    }

    // ---------- 目录持久化 ----------

    private fun getSavedDirs(): List<MusicDir> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_DIR_URIS, "") ?: ""
        if (str.isBlank()) return emptyList()
        return str.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val idx = line.indexOf('|')
            val (name, uriStr) = if (idx >= 0) {
                line.substring(0, idx) to line.substring(idx + 1)
            } else {
                // 兼容旧格式：只有 uri
                "" to line
            }
            try {
                val uri = Uri.parse(uriStr)
                MusicDir(if (name.isBlank()) dirDisplayName(uri) else name, uri)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun saveDirs(dirs: List<MusicDir>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = dirs.joinToString("\n") { "${it.name}|${it.uri}" }
        prefs.edit().putString(KEY_DIR_URIS, str).apply()
    }

    private fun addDir(dir: MusicDir) {
        val existing = getSavedDirs().toMutableList()
        if (existing.any { it.uri.toString() == dir.uri.toString() }) return
        existing.add(dir)
        saveDirs(existing)
    }

    private fun removeDir(uri: Uri) {
        val existing = getSavedDirs().toMutableList()
        existing.removeAll { it.uri.toString() == uri.toString() }
        saveDirs(existing)
        // 级联清理：删除该目录自身及其所有子目录的缓存
        val allUris = collectCachedDirUris(uri)
        for (u in allUris) {
            clearSongCache(u)
            clearDirCache(u)
            clearDirPosition(u)
        }
        if (activeDirUri?.toString() == uri.toString()) {
            activeDirUri = null
        }
    }

    /**
     * 递归收集某目录自身及其所有「已缓存」子目录的 uri。
     * 依据目录结构缓存（loadDirCache 读出的子文件夹列表）逐层展开，
     * 用 visited 集合防止环形引用导致的死循环。
     */
    private fun collectCachedDirUris(root: Uri): Set<Uri> {
        val result = LinkedHashSet<Uri>()
        val visited = HashSet<String>()
        fun walk(dirUri: Uri) {
            val key = dirUri.toString()
            if (!visited.add(key)) return
            result.add(dirUri)
            val cache = loadDirCache(dirUri) ?: return
            for (sub in cache.second) {
                walk(sub.uri)
            }
        }
        walk(root)
        return result
    }

    private fun setActiveDir(uri: Uri) {
        // 切换到不同目录前，先保存旧目录当前的播放位置
        val old = activeDirUri
        if (old != null && old.toString() != uri.toString()) {
            saveCurrentDirPosition()
        }
        activeDirUri = uri
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_DIR, uri.toString()).apply()
    }

    private fun getActiveDir(): Uri? {
        if (activeDirUri != null) return activeDirUri
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_ACTIVE_DIR, "") ?: ""
        activeDirUri = if (str.isBlank()) null else Uri.parse(str)
        return activeDirUri
    }

    private fun autoLoadDirectoriesAsync() {
        val active = getActiveDir() ?: return
        loadDirSongs(active)
    }

    private fun rescanSongsAsync() {
        val active = getActiveDir() ?: return
        loadDirSongs(active)
    }

    // ---------- 歌曲列表缓存 ----------

    /** 缓存文件名：用目录 uri 的 hash 作文件名，避免非法字符 */
    private fun cacheFileFor(dirUri: Uri): java.io.File {
        val dir = java.io.File(filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val name = dirUri.toString().hashCode().toString() + ".txt"
        return java.io.File(dir, name)
    }

    /** 把歌曲列表写入缓存 */
    private fun saveSongCache(dirUri: Uri, list: List<Song>) {
        try {
            val sb = StringBuilder()
            for (s in list) {
                sb.append(s.name).append('|').append(s.uri.toString())
                if (s.path != null) sb.append('|').append(s.path)
                sb.append('\n')
            }
            cacheFileFor(dirUri).writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    /** 读取缓存（可能为空） */
    private fun loadSongCache(dirUri: Uri): List<Song> {
        return try {
            val f = cacheFileFor(dirUri)
            if (!f.exists()) return emptyList()
            f.readText().split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 2) return@mapNotNull null
                val name = parts[0]
                val uriStr = parts[1]
                val path = if (parts.size >= 3) parts[2] else null
                try {
                    Song(name, Uri.parse(uriStr), path)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun clearSongCache(dirUri: Uri) {
        try {
            cacheFileFor(dirUri).delete()
        } catch (_: Exception) {
        }
    }

    // ---------- 目录结构缓存（子文件夹列表） ----------

    /** 目录结构缓存文件名 */
    private fun dirCacheFileFor(dirUri: Uri): java.io.File {
        val dir = java.io.File(filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val name = "dir_" + dirUri.toString().hashCode().toString() + ".txt"
        return java.io.File(dir, name)
    }

    /** 保存目录结构缓存：第一行是 hasSub 标记，后面是子文件夹 name|uri */
    private fun saveDirCache(dirUri: Uri, hasSub: Boolean, subDirs: List<DirEntry>) {
        try {
            val sb = StringBuilder()
            sb.append(if (hasSub) "1" else "0").append('\n')
            for (d in subDirs) {
                sb.append(d.name).append('|').append(d.uri.toString()).append('\n')
            }
            dirCacheFileFor(dirUri).writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    /** 读取目录结构缓存。返回 null 表示没有缓存。 */
    private fun loadDirCache(dirUri: Uri): Pair<Boolean, List<DirEntry>>? {
        return try {
            val f = dirCacheFileFor(dirUri)
            if (!f.exists()) return null
            val lines = f.readText().split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) return null
            val hasSub = lines[0] == "1"
            val subDirs = lines.drop(1).mapNotNull { line ->
                val idx = line.indexOf('|')
                if (idx < 0) return@mapNotNull null
                val name = line.substring(0, idx)
                val uriStr = line.substring(idx + 1)
                try {
                    DirEntry(name, Uri.parse(uriStr), true)
                } catch (_: Exception) {
                    null
                }
            }
            hasSub to subDirs
        } catch (_: Exception) {
            null
        }
    }

    private fun clearDirCache(dirUri: Uri) {
        try {
            dirCacheFileFor(dirUri).delete()
        } catch (_: Exception) {
        }
    }

    private fun hasSongCache(dirUri: Uri): Boolean = cacheFileFor(dirUri).exists()

    /**
     * 加载某个目录「这一层」的歌曲：
     * 有缓存就直接读缓存（不再扫描）；无缓存才扫描一次并保存缓存。
     */
    private fun loadDirSongs(dirUri: Uri) {
        val cached = loadSongCache(dirUri)
        if (cached.isNotEmpty()) {
            // 有缓存：直接用，不再扫描
            playbackService?.clearAndStop()
            songs = cached
            updateSongList()
            playbackService?.setPlaylist(songs)
            tryResumeDirPosition(dirUri)
            return
        }

        // 无缓存：扫描一次，存缓存
        val seq = ++scanRequestSeq
        showScanProgress("正在扫描 ${dirDisplayName(dirUri)} ...")
        Thread {
            // 本地路径走 File 扫描，SAF 目录走 DocumentFile 扫描
            val scanned = if (isLocalUri(dirUri)) {
                DirScanner.listLocalSongsInDir(dirUri.path ?: "")
            } else {
                DirScanner.listSongsInDir(this, dirUri)
            }
            if (scanned.isNotEmpty()) {
                saveSongCache(dirUri, scanned)
            }
            runOnUiThread {
                // 若期间又发起了新的扫描，丢弃本次结果
                if (seq != scanRequestSeq) return@runOnUiThread
                hideScanProgress()
                playbackService?.clearAndStop()
                songs = scanned
                updateSongList()
                playbackService?.setPlaylist(songs)
                tryResumeDirPosition(dirUri)
            }
        }.start()
    }

    private fun updateSongList() {
        val names = songs.map { it.name }
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view: View = convertView ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_song, parent, false)
                val title = view.findViewById<TextView>(R.id.tv_item_title)
                title.text = names[position]
                // 区间删除模式下文字变色：普通项绿色，起点项红色；非模式白色
                title.setTextColor(
                    when {
                        !rangeDeleteMode -> 0xFFFFFFFF.toInt()
                        position == rangeStartIndex -> 0xFFFF5252.toInt()
                        else -> 0xFF1DB954.toInt()
                    }
                )
                return view
            }
        }
        listSongs.adapter = adapter
    }

    // ---------- 区间删除 ----------

    /** 进入区间删除模式 */
    private fun enterRangeDeleteMode() {
        if (songs.isEmpty()) {
            Toast.makeText(this, "列表为空，无可删除", Toast.LENGTH_SHORT).show()
            return
        }
        rangeDeleteMode = true
        rangeStartIndex = -1
        updateSongList()
        Toast.makeText(this, "请点击区间的第一个文件\n（点「目录」按钮取消）", Toast.LENGTH_LONG).show()
    }

    /** 区间删除模式下点击列表项 */
    private fun onRangeDeleteTap(position: Int) {
        if (rangeStartIndex < 0) {
            // 第一次点击：标记起点
            rangeStartIndex = position
            updateSongList()
            Toast.makeText(this, "起点：${songs[position].name}\n请点击区间的最后一个文件", Toast.LENGTH_LONG).show()
        } else {
            // 第二次点击：标记终点，弹出确认
            val start = minOf(rangeStartIndex, position)
            val end = maxOf(rangeStartIndex, position)
            val count = end - start + 1
            confirmRangeDelete(start, end, count)
        }
    }

    /** 确认区间删除 */
    private fun confirmRangeDelete(start: Int, end: Int, count: Int) {
        val firstName = songs[start].name
        val lastName = songs[end].name
        AlertDialog.Builder(this)
            .setTitle("区间删除")
            .setMessage("将删除第 ${start + 1} 到第 ${end + 1} 个文件，共 $count 个：\n\n$firstName\n...\n$lastName\n\n是否同时彻底删除本地源文件？")
            .setPositiveButton("彻底删除") { _, _ -> executeRangeDelete(start, end, true) }
            .setNeutralButton("仅移出列表") { _, _ -> executeRangeDelete(start, end, false) }
            .setNegativeButton("取消") { _, _ -> resetRangeDelete() }
            .show()
    }

    /** 执行区间删除 */
    private fun executeRangeDelete(start: Int, end: Int, deleteSource: Boolean) {
        val toDelete = songs.subList(start, end + 1).toList()
        var deletedCount = 0
        var failCount = 0
        if (deleteSource) {
            // 彻底删除本地源文件：优先用本地路径 File 删除，否则走 SAF
            for (song in toDelete) {
                val ok = if (song.path != null) {
                    try { File(song.path).delete() } catch (_: Exception) { false }
                } else {
                    UriHelper.deleteFile(this, song.uri)
                }
                if (ok) deletedCount++ else failCount++
            }
        }
        // 从列表移除（无论是否彻底删除源文件，都从列表移除）
        val remaining = songs.filterIndexed { i, _ -> i !in start..end }
        songs = remaining
        updateSongList()
        playbackService?.setPlaylist(songs)
        // 清除当前目录的歌曲缓存，重新写
        val active = getActiveDir()
        if (active != null) {
            saveSongCache(active, songs)
        }
        // 如果删掉了正在播放的歌，停止播放
        playbackService?.clearAndStop()
        tvSongTitle.text = if (songs.isNotEmpty()) songs[0].name else "（此目录没有音乐）"

        resetRangeDelete()
        val msg = if (deleteSource) {
            if (failCount > 0) {
                "已彻底删除 $deletedCount 个文件，$failCount 个删除失败"
            } else {
                "已彻底删除 $deletedCount 个文件"
            }
        } else {
            "已从列表移除 ${toDelete.size} 个文件"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** 退出区间删除模式 */
    private fun resetRangeDelete() {
        rangeDeleteMode = false
        rangeStartIndex = -1
        updateSongList()
    }

    // ---------- 播放 ----------

    private fun playAt(index: Int) {
        val svc = playbackService ?: return
        if (songs.isEmpty()) {
            Toast.makeText(this, "请先导入目录", Toast.LENGTH_SHORT).show()
            return
        }
        svc.setPlaylist(songs)
        svc.playAt(index)
    }

    private fun togglePlayPause() {
        val svc = playbackService ?: return
        if (songs.isEmpty()) {
            Toast.makeText(this, "请先导入目录", Toast.LENGTH_SHORT).show()
            return
        }
        svc.setPlaylist(songs)
        svc.togglePlayPause()
    }

    // ---------- 睡眠倒计时 ----------

    private fun showSleepDialog() {
        val options = arrayOf("关闭", "15 分钟", "20 分钟", "30 分钟", "40 分钟", "50 分钟", "60 分钟", "90 分钟")
        val minutesArr = longArrayOf(0, 15, 20, 30, 40, 50, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("睡眠倒计时")
            .setItems(options) { _, which ->
                val minutes = minutesArr[which]
                if (minutes <= 0) {
                    cancelTimer()
                } else {
                    startTimer(minutes * 60L * 1000L)
                }
            }
            .show()
    }

    private fun startTimer(totalMs: Long) {
        timerRemainingMs = totalMs
        timerRunning = true
        handler.removeCallbacks(timerTick)
        handler.postDelayed(timerTick, 1000)
        btnSleep.text = "睡眠 " + formatRemain(timerRemainingMs)
    }

    private fun cancelTimer() {
        timerRunning = false
        handler.removeCallbacks(timerTick)
        btnSleep.text = "睡眠"
    }

    private fun shutdownApp() {
        playbackService?.stopPlayback()
        handler.removeCallbacksAndMessages(null)
        Toast.makeText(this, "睡眠时间到，正在关闭", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            finishAndRemoveTask()
        }, 800)
    }

    // ---------- 工具 ----------

    // ---------- 记忆播放位置 ----------

    private fun showScanProgress(msg: String) {
        runOnUiThread {
            try {
                if (scanDialog != null && scanDialog?.isShowing == true) {
                    scanDialog?.setMessage(msg)
                    return@runOnUiThread
                }
                scanDialog = android.app.ProgressDialog(this)
                scanDialog?.setMessage(msg)
                scanDialog?.setCancelable(false)
                scanDialog?.show()
            } catch (_: Exception) {
            }
        }
    }

    private fun hideScanProgress() {
        runOnUiThread {
            try {
                scanDialog?.dismiss()
            } catch (_: Exception) {
            }
            scanDialog = null
        }
    }

    private fun saveLastSong(uri: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SONG_URI, uri).apply()
    }

    private fun saveLastPosition(position: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_LAST_POSITION, position).apply()
    }

    private fun getLastSongUri(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val s = prefs.getString(KEY_LAST_SONG_URI, "") ?: ""
        return if (s.isBlank()) null else s
    }

    private fun getLastPosition(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_LAST_POSITION, 0)
    }

    // ---------- 按目录记忆播放位置 ----------

    /** 目录位置记忆的 key：pos_<uri hash> */
    private fun posKeyFor(dirUri: Uri): String = KEY_POS_PREFIX + dirUri.toString().hashCode().toString()

    /** 保存某目录的播放位置（songUri 为空表示未播放，清掉） */
    private fun saveDirPosition(dirUri: Uri, songUri: String?, position: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = posKeyFor(dirUri)
        if (songUri.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, "$songUri|$position").apply()
        }
    }

    /** 读取某目录的播放位置，返回 (songUri, position)，无记录返回 null */
    private fun loadDirPosition(dirUri: Uri): Pair<String, Int>? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(posKeyFor(dirUri), "") ?: ""
        if (raw.isBlank()) return null
        val idx = raw.indexOf('|')
        if (idx < 0) return null
        val songUri = raw.substring(0, idx)
        val pos = raw.substring(idx + 1).toIntOrNull() ?: 0
        return songUri to pos
    }

    /** 清除某目录的位置记忆 */
    private fun clearDirPosition(dirUri: Uri) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(posKeyFor(dirUri)).apply()
    }

    /**
     * 把当前正在播放的目录位置保存下来（在切换目录前调用）。
     */
    private fun saveCurrentDirPosition() {
        val svc = playbackService ?: return
        val dirUri = activeDirUri ?: return
        val song = svc.currentSong()
        if (song != null) {
            saveDirPosition(dirUri, song.uri.toString(), svc.currentPosition())
        }
    }


    /**
     * 尝试恢复上次播放：在歌曲列表中找到上次那首歌，定位并 seek 到上次进度。
     * 只在启动时执行一次（pendingResume 门控），手动切目录不触发。
     */
    private fun tryResumeLastPlayback() {
        if (!pendingResume) return
        val lastUri = getLastSongUri() ?: return
        val lastPos = getLastPosition()

        // 在列表里找上次的歌曲
        val idx = songs.indexOfFirst { it.uri.toString() == lastUri }
        if (idx < 0) return

        // 找到后立即消费掉恢复标记，避免重复触发
        pendingResume = false

        locateAndSeek(idx, lastPos)
    }

    /**
     * 尝试恢复「某个目录」上次播放的位置：
     * 读该目录的位置记忆，在歌曲列表里找到对应歌曲并 seek 到进度。
     * 找不到记忆或歌曲已不存在时，不定位（保持列表显示，不自动播第一首）。
     */
    private fun tryResumeDirPosition(dirUri: Uri) {
        val mem = loadDirPosition(dirUri) ?: return
        val (songUri, pos) = mem
        val idx = songs.indexOfFirst { it.uri.toString() == songUri }
        if (idx < 0) return

        locateAndSeek(idx, pos)
    }

    /**
     * 定位到第 idx 首歌并 seek 到 pos（不自动播放，保持暂停，等用户决定）。
     */
    private fun locateAndSeek(idx: Int, pos: Int) {
        val svc = playbackService ?: return
        svc.setPlaylist(songs)
        svc.playAt(idx)
        // 延迟等 prepare 完成后 seek，并暂停（让用户自己决定是否继续播）
        handler.postDelayed({
            if (pos > 0) {
                svc.seekTo(pos)
            }
            if (svc.isPlaying()) {
                svc.togglePlayPause()
            }
            // 同步 UI 进度条到恢复位置
            if (pos > 0 && svc.duration() > 0) {
                seekBar.max = svc.duration()
                seekBar.progress = pos
                tvCurrentTime.text = formatTime(pos.toLong())
                tvTotalTime.text = formatTime(svc.duration().toLong())
            }
        }, 800)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun formatRemain(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else if (minutes > 0) {
            String.format("%d:%02d", minutes, seconds)
        } else {
            String.format("%d 秒", seconds)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(progressTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressTick)
        // 切后台/锁屏前，立即保存一次当前播放进度
        val svc = playbackService
        if (svc != null && svc.currentSong() != null) {
            saveLastSong(svc.currentSong()!!.uri.toString())
            saveLastPosition(svc.currentPosition())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // 退出前保存进度
        val svc = playbackService
        if (svc != null && svc.currentSong() != null) {
            saveLastSong(svc.currentSong()!!.uri.toString())
            saveLastPosition(svc.currentPosition())
        }
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
