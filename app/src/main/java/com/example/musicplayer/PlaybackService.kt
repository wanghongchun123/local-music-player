package com.example.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.IOException

/**
 * 前台播放服务：让音乐在锁屏、切后台时继续播放。
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.example.musicplayer.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT"
        const val ACTION_PREV = "com.example.musicplayer.ACTION_PREV"
        const val ACTION_STOP = "com.example.musicplayer.ACTION_STOP"
        // 睡眠定时停止后「亮屏自动续播」的标记与配置
        const val ACTION_SLEEP_STOP = "com.example.musicplayer.ACTION_SLEEP_STOP"
        const val PREFS_NAME = "music_player_prefs"
        const val KEY_SLEEP_RESUME = "sleep_resume"
        const val KEY_LAST_SONG_URI = "last_song_uri"
        const val KEY_LAST_POSITION = "last_position"
        const val KEY_RESUME_POS = "resume_pos"
        // 亮屏自动播放开关 + 续播倒计时分钟数（与 MainActivity 共用）
        const val KEY_SCREEN_RESUME_ON = "screen_resume_on"
        const val KEY_SCREEN_RESUME_MIN = "screen_resume_min"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    var songs: List<Song> = emptyList()
    var currentIndex = -1
        private set

    private var mediaPlayer: MediaPlayer? = null
    private var pendingAfd: android.content.res.AssetFileDescriptor? = null
    private var isForeground = false

    // 音频焦点：其他 App 播放声音时自动暂停本播放器
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    // 焦点监听器必须持有强引用，否则可能被 GC 回收导致回调失效
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    // 因焦点丢失而暂停的标记：焦点恢复（GAIN）时据此自动续播
    private var pausedByFocusLoss = false
    // 前台 App 检测（无障碍服务广播）：抖音/快手进入暂停、退出续播
    private var pausedByThirdParty = false // 因第三方 App 到前台而暂停的标记
    private var foregroundReceiver: BroadcastReceiver? = null
    // 当前前台是否为第三方 App（用于抑制抖音退后台后的声音残留误暂停）
    private var currentForegroundIsThirdParty = false

    // 音频播放配置监听：检测系统里其他 App 开始/停止播放（抖音在荣耀上不抢焦点也不产生窗口事件，
    // 只能用 AudioPlaybackCallback 监听全系统音频播放状态）
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    // 亮屏自动续播相关
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null
    // 睡眠等待续播状态：是否正在等待亮屏
    private var sleepWaiting = false
    // 亮屏续播后是否启动睡眠倒计时（仅睡眠定时触发时启动，焦点丢失续播不启动）
    private var startSleepTimerAfterResume = false
    // 续播后的倒计时：剩余毫秒（<=0 表示未启动）
    private var resumeTimerRemainingMs = 0L
    private val resumeTimerTick = object : Runnable {
        override fun run() {
            if (resumeTimerRemainingMs <= 0) return
            resumeTimerRemainingMs -= 1000
            if (resumeTimerRemainingMs <= 0) {
                // 续播倒计时到点：停止播放，回到「睡眠等待」状态，
                // 等待下次亮屏再次续播（服务保持存活，歌曲列表不丢，可无限循环）
                enterSleepWaitInternal()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    var onSongChanged: ((Song?) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initAudioFocus()
        initAudioPlaybackCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> stopSelf()
            ACTION_SLEEP_STOP -> enterSleepWait()
        }
        return START_NOT_STICKY
    }

    fun setPlaylist(list: List<Song>) {
        songs = list
    }

    // ---------- 音频焦点 ----------

    /** 初始化音频焦点监听：其他 App 播放声音时本播放器自动暂停 */
    private fun initAudioFocus() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            android.util.Log.d("MusicPlayer", "焦点回调 focusChange=$focusChange (LOSS=1,TRANSIENT=2,DUCK=3,GAIN=0)")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 焦点丢失（抖音/快手/网易云/微信语音等）：暂停，等待焦点恢复后自动续播
                    pauseForFocusLoss()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 焦点恢复：若之前因焦点丢失而暂停，则自动续播
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        resumeAfterFocusGain()
                    }
                }
            }
        }
        // 保存强引用，防止 listener 被 GC 回收
        focusListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener)
                .build()
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    /**
     * 初始化音频播放配置监听：监听全系统音频播放器的开始/停止。
     * 抖音极速版在荣耀 Magic4 上既不抢音频焦点、也不产生无障碍窗口事件、
     * rootInActiveWindow 也拿不到它，只有 AudioPlaybackCallback 能可靠检测到它开始/停止播放。
     */
    private fun initAudioPlaybackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val am = audioManager ?: return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                if (configs == null) return
                // getActivePlaybackConfigurations 返回的列表只含「活跃播放中」的配置（系统已过滤）。
                // 普通 App 拿不到其他 App 的 uid/包名（getClientUid/getPlayerState/isActive 都是 @SystemApi），
                // 只能用公开的 getAudioAttributes() 的 usage/contentType 区分：
                //   自己 = USAGE_MEDIA + CONTENT_TYPE_MUSIC；
                //   其他活跃流（抖音/微信语音/导航等）→ 视为第三方在播放。
                var activeMediaCount = 0
                var foreignActive = false
                for (cfg in configs) {
                    val attrs = cfg.audioAttributes
                    if (attrs.usage == AudioAttributes.USAGE_MEDIA &&
                        attrs.contentType == AudioAttributes.CONTENT_TYPE_MUSIC
                    ) {
                        activeMediaCount++
                    } else {
                        foreignActive = true
                    }
                }
                val othersActive = foreignActive || activeMediaCount > 1
                android.util.Log.d(
                    "MusicPlayer",
                    "AudioPlaybackCallback othersActive=$othersActive (foreign=$foreignActive media=$activeMediaCount) fg3rd=$currentForegroundIsThirdParty paused3rd=$pausedByThirdParty playing=${mediaPlayer?.isPlaying}"
                )
                // 只在「前台确实在第三方 App」时才因第三方出声而暂停。
                // 前台已切回桌面/自己后，抖音退后台的声音残留（foreign 仍短暂为 true）
                // 不应再触发暂停，否则会打断刚完成的续播。
                if (othersActive && currentForegroundIsThirdParty) {
                    onOtherAudioStarted()
                }
            }
        }
        audioPlaybackCallback = cb
        am.registerAudioPlaybackCallback(cb, handler)
        android.util.Log.d("MusicPlayer", "AudioPlaybackCallback 已注册")
    }

    /** 其他 App 开始播放时，暂停本播放器 */
    private fun onOtherAudioStarted() {
        val mp = mediaPlayer
        if (mp != null && mp.isPlaying && !pausedByThirdParty) {
            android.util.Log.d("MusicPlayer", "检测到其他 App 播放，暂停本播放器")
            pausedByThirdParty = true
            pausedByFocusLoss = false
            mp.pause()
            onPlayStateChanged?.invoke(false)
            updateNotification()
        }
    }

    /** 播放前申请音频焦点，成功才真正播放 */
    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            am.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            am.abandonAudioFocusRequest(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    /** 因焦点丢失暂停播放（抖音退出不还焦点，续播统一由前台切换检测触发） */
    private fun pauseForFocusLoss() {
        val mp = mediaPlayer
        android.util.Log.d("MusicPlayer", "pauseForFocusLoss 调用 isPlaying=${mp?.isPlaying} fg3rd=$currentForegroundIsThirdParty")
        // 只在「前台确实是第三方 App」时才响应焦点丢失暂停；
        // 前台已切回桌面后，抖音退后台声音残留触发的 LOSS 不应再打断续播。
        if (mp != null && mp.isPlaying && currentForegroundIsThirdParty) {
            pausedByFocusLoss = true
            pausedByThirdParty = true
            mp.pause()
            onPlayStateChanged?.invoke(false)
            updateNotification()
        }
    }

    /** 焦点恢复后自动续播（同时也是亮屏续播等所有续播的统一出口） */
    private fun resumeAfterFocusGain() {
        // 清理等待状态
        pausedByFocusLoss = false
        pausedByThirdParty = false
        sleepWaiting = false
        cancelScreenReceiver()
        val mp = mediaPlayer
        if (mp != null && !mp.isPlaying) {
            // 重新申请焦点后再续播
            requestAudioFocus()
            mp.start()
            onPlayStateChanged?.invoke(true)
            goForeground()
            updateNotification()
        }
    }

    // ---------- 前台 App 检测（无障碍服务广播） ----------

    /** 注册前台切换广播，播放期间常驻监听 */
    private fun startForegroundWatch() {
        if (foregroundReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.getStringExtra(ForegroundAccessibilityService.EXTRA_PACKAGE)
                    ?: return
                onForegroundChanged(pkg)
            }
        }
        registerReceiver(
            receiver,
            IntentFilter(ForegroundAccessibilityService.ACTION_FOREGROUND_CHANGED),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED
            else
                0
        )
        foregroundReceiver = receiver
        android.util.Log.d("MusicPlayer", "前台检测广播已注册")
    }

    private fun stopForegroundWatch() {
        foregroundReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        foregroundReceiver = null
    }

    /** 前台 App 变化时的处理：第三方 App 进入暂停，退出续播 */
    private fun onForegroundChanged(pkg: String) {
        val isSelf = pkg == packageName
        val isSystem = pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg == "com.android.settings" ||
            pkg.startsWith("com.hihonor.") ||
            pkg.startsWith("com.huawei.") ||
            (pkg.startsWith("com.android.") && pkg.contains("launcher"))
        val isThirdParty = !isSelf && !isSystem

        // 更新「前台是否第三方」状态，供 AudioPlaybackCallback 判断是否抑制声音残留
        currentForegroundIsThirdParty = isThirdParty

        android.util.Log.d("MusicPlayer", "前台变化 pkg=$pkg isThirdParty=$isThirdParty paused3rd=$pausedByThirdParty playing=${mediaPlayer?.isPlaying}")

        val mp = mediaPlayer
        if (pausedByThirdParty) {
            // 之前因第三方 App 而暂停：现在前台切回自己/桌面/系统界面，自动续播
            if (!isThirdParty) {
                android.util.Log.d("MusicPlayer", "第三方退出，触发续播")
                pausedByThirdParty = false
                resumeAfterFocusGain()
            }
        } else {
            // 正在播放：前台切到第三方 App（抖音/快手等），主动暂停
            if (mp != null && mp.isPlaying && isThirdParty) {
                android.util.Log.d("MusicPlayer", "第三方进入，触发暂停")
                pausedByThirdParty = true
                pausedByFocusLoss = false
                mp.pause()
                onPlayStateChanged?.invoke(false)
                updateNotification()
            }
        }
    }

    /** 停止当前播放并清空列表（不销毁服务），用于切换目录 */
    fun clearAndStop() {
        releasePlayer()
        songs = emptyList()
        currentIndex = -1
        onPlayStateChanged?.invoke(false)
        onSongChanged?.invoke(null)
    }

    fun playAt(index: Int) {
        if (songs.isEmpty()) return
        if (index < 0 || index >= songs.size) return
        currentIndex = index
        val song = songs[index]
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 优先用本地路径播放（自建浏览界面选中的本地文件），否则走 SAF Uri
            val path = song.path
            if (path != null) {
                mp.setDataSource(path)
            } else {
                // 用 FileDescriptor 播放，绕开 Uri 解析，兼容性最好
                val afd = contentResolver.openAssetFileDescriptor(song.uri, "r")
                if (afd != null) {
                    // 关键：不能立即 close，否则 prepareAsync 读取数据时 fd 已失效，
                    // 会导致"第一首能播、切歌后失败"。让 MediaPlayer 持有 afd，
                    // 在 prepare 完成（onPrepared）或出错时才关闭。
                    pendingAfd = afd
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } else {
                    // 兜底：直接传 Uri
                    mp.setDataSource(this, song.uri)
                }
            }
            mp.setOnPreparedListener { p ->
                try {
                    closePendingAfd()
                    // 播放前先申请音频焦点，避免与其他 App 抢声音
                    requestAudioFocus()
                    p.start()
                    onSongChanged?.invoke(song)
                    onPlayStateChanged?.invoke(true)
                    goForeground()
                    // 播放期间常驻前台 App 轮询，检测第三方 App 进入/退出
                    startForegroundWatch()
                } catch (e: Exception) {
                    closePendingAfd()
                    android.util.Log.e("MusicPlayer", "准备后启动失败", e)
                }
            }
            mp.setOnCompletionListener { playNext() }
            mp.setOnErrorListener { _, what, extra ->
                closePendingAfd()
                android.util.Log.e("MusicPlayer", "播放失败 what=$what extra=$extra song=${song.name}")
                onSongChanged?.invoke(song)
                onPlayStateChanged?.invoke(false)
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: IOException) {
            closePendingAfd()
            android.util.Log.e("MusicPlayer", "播放异常 ${song.name}", e)
            releasePlayer()
            onPlayStateChanged?.invoke(false)
        } catch (e: Exception) {
            closePendingAfd()
            android.util.Log.e("MusicPlayer", "播放异常 ${song.name}", e)
            releasePlayer()
            onPlayStateChanged?.invoke(false)
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp == null) {
            if (songs.isNotEmpty()) {
                playAt(if (currentIndex >= 0) currentIndex else 0)
            }
            return
        }
        if (mp.isPlaying) {
            mp.pause()
            onPlayStateChanged?.invoke(false)
            abandonAudioFocus()
            stopForegroundWatch()
        } else {
            requestAudioFocus()
            mp.start()
            onPlayStateChanged?.invoke(true)
            goForeground()
            startForegroundWatch()
        }
        updateNotification()
    }

    fun playNext() {
        if (songs.isEmpty()) return
        playAt((currentIndex + 1) % songs.size)
    }
    fun playPrevious() {
        if (songs.isEmpty()) return
        val mp = mediaPlayer
        if (mp != null && mp.currentPosition > 3000) {
            mp.seekTo(0)
            onProgress?.invoke(0, mp.duration)
            return
        }
        val prev = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        playAt(prev)
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun currentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun duration(): Int = mediaPlayer?.duration ?: 0

    fun currentSong(): Song? =
        if (currentIndex in songs.indices) songs[currentIndex] else null

    fun stopPlayback() {
        releasePlayer()
        onPlayStateChanged?.invoke(false)
        onSongChanged?.invoke(null)
        stopSelf()
    }

    // ---------- 睡眠停止 + 亮屏自动续播 ----------

    /**
     * 睡眠定时到点：若「亮屏自动播放」开启，暂停播放、保留服务并注册亮屏/解锁广播，
     * 等待下次亮屏时自动续播；否则直接停止服务。
     */
    private fun enterSleepWait() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val resumeOn = prefs.getBoolean(KEY_SCREEN_RESUME_ON, false)
        if (!resumeOn) {
            // 开关关闭：直接停止播放并退出
            releasePlayer()
            onPlayStateChanged?.invoke(false)
            onSongChanged?.invoke(null)
            stopSelf()
            return
        }
        // 睡眠定时触发的续播：亮屏续播后要重新启动睡眠倒计时
        startSleepTimerAfterResume = true
        enterSleepWaitInternal()
    }

    /** 真正进入等待亮屏续播状态（睡眠定时、续播倒计时、焦点丢失三处复用） */
    private fun enterSleepWaitInternal() {
        if (sleepWaiting) return
        sleepWaiting = true

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 记录当前要恢复的歌曲 uri 和位置
        val songUri = currentSong()?.uri?.toString()
        val pos = mediaPlayer?.currentPosition ?: 0
        prefs.edit()
            .putBoolean(KEY_SLEEP_RESUME, true)
            .putString(KEY_RESUME_POS, if (songUri != null) "$songUri|$pos" else "")
            .apply()

        // 暂停播放（不销毁服务，保留歌曲列表和播放器状态）
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                onPlayStateChanged?.invoke(false)
            }
        }
        // 移除续播倒计时（若之前有）
        handler.removeCallbacks(resumeTimerTick)
        resumeTimerRemainingMs = 0L

        // 更新通知为「等待亮屏续播」
        updateNotification()

        // 注册亮屏/解锁广播
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                    onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED
            else
                0
        )
        screenReceiver = receiver
    }

    private fun onScreenOn() {
        if (!sleepWaiting) return
        sleepWaiting = false
        cancelScreenReceiver()
        // 清理焦点暂停标记
        pausedByFocusLoss = false
        pausedByThirdParty = false

        // 清除睡眠标记
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SLEEP_RESUME).apply()

        // 恢复播放
        if (songs.isEmpty()) {
            stopSelf()
            return
        }
        val resumeStr = prefs.getString(KEY_RESUME_POS, "") ?: ""
        val resumeUri = resumeStr.substringBefore('|')
        val resumePos = resumeStr.substringAfter('|', "0").toIntOrNull() ?: 0

        var idx = songs.indexOfFirst { it.uri.toString() == resumeUri }
        if (idx < 0) idx = currentIndex.coerceIn(0, songs.size - 1)
        if (idx < 0) idx = 0

        playAt(idx)
        handler.postDelayed({
            if (resumePos > 0) {
                mediaPlayer?.seekTo(resumePos)
            }
        }, 800)

        // 按标记决定是否启动「亮屏续播倒计时」（仅睡眠定时触发时启动）
        if (startSleepTimerAfterResume) {
            startSleepTimerAfterResume = false
            val resumeMin = prefs.getInt(KEY_SCREEN_RESUME_MIN, 15)
            if (resumeMin > 0) {
                resumeTimerRemainingMs = resumeMin * 60L * 1000L
                handler.removeCallbacks(resumeTimerTick)
                handler.postDelayed(resumeTimerTick, 1000)
            }
        }
    }

    private fun cancelScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
    }

    private fun closePendingAfd() {
        pendingAfd?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        pendingAfd = null
    }

    private fun releasePlayer() {
        closePendingAfd()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
    }

    // ---------- 前台服务 ----------

    private fun goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            isForeground = true
        } catch (e: Exception) {
            // 前台启动失败不影响播放，记录日志
            android.util.Log.e("MusicPlayer", "前台服务启动失败", e)
        }
    }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = currentSong()
        val title = song?.name ?: "本地音乐播放器"
        val isPlaying = isPlaying()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon =
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (isPlaying) "正在播放" else "已暂停")
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "暂停" else "播放", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .addAction(android.R.drawable.ic_media_play, "关闭", stopIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        cancelScreenReceiver()
        stopForegroundWatch()
        audioPlaybackCallback?.let {
            try {
                audioManager?.unregisterAudioPlaybackCallback(it)
            } catch (_: Exception) {
            }
        }
        audioPlaybackCallback = null
        handler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        releasePlayer()
        super.onDestroy()
    }
}
