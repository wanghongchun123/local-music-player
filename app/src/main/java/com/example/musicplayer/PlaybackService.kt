package com.example.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
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

    var onSongChanged: ((Song?) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    fun setPlaylist(list: List<Song>) {
        songs = list
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
                    p.start()
                    onSongChanged?.invoke(song)
                    onPlayStateChanged?.invoke(true)
                    goForeground()
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
        } else {
            mp.start()
            onPlayStateChanged?.invoke(true)
            goForeground()
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
        releasePlayer()
        super.onDestroy()
    }
}
