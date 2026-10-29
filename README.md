# 本地音乐播放器

一个专为「按文件夹听本地音乐」设计的离线安卓音乐播放器。原生 Kotlin 开发，纯本地运行，无网络依赖。

## 一、核心功能

### 1. 目录式音乐管理
- 按文件夹组织音乐，目录管理支持**添加、切换、删除**目录（删除支持多选 + 全选）
- 添加目录使用**自建文件夹浏览界面**，不依赖系统文件管理器
- 选中父目录后**自动递归添加最底层的叶子目录**，跳过中间层，目录管理直接显示干净的子文件夹名
- 内置**「常用文件夹」**区块：长按收藏常用目录，点一下就快速跳转定位

### 2. 播放能力
- 目录顺序播放、单曲播放，支持进度条拖动
- `MediaPlayer + FileDescriptor` 播放方式，绕开 SAF content Uri 兼容问题，切歌稳定
- **前台服务**常驻播放，锁屏不断播，通知栏可控制
- **睡眠定时关机**：关闭 / 15 / 20 / 30 / 40 / 50 / 60 / 90 分钟可选

### 3. 记忆与恢复
- 启动自动读取上次目录，**记忆上次播放的歌曲和进度**，重启自动恢复
- **按目录独立记忆播放位置**：不同目录各自记住进度，切换不串台

### 4. 文件管理
- **区间删除**：长按歌曲进入区间删除模式，批量删除一段歌曲（起点标红、区间标绿，整行文字变色）
- 支持**彻底删除本地文件**（可选）

### 5. 纯缓存策略
- 目录扫描一次后**永久缓存**，重启不再重复扫描，秒开
- 删除目录时**级联清理**自身 + 所有子目录的缓存和位置记忆

## 二、特色亮点

| 特色 | 说明 |
|------|------|
| 🎨 深色主题 | 全黑背景 + 绿色高亮（#1DB954），类 OPlayer 风格 |
| 📁 本地路径体系 | 从 SAF 迁移到本地 File 路径 + MANAGE_EXTERNAL_STORAGE，操作更直接 |
| 🧭 自建浏览界面 | 完全可控的文件选择体验，摆脱系统选择器不可改的按钮文案 |
| 🔁 竞态防护 | scanRequestSeq 请求序号机制，防止快速切换目录时旧结果覆盖新结果 |
| 💾 按目录记忆 | 每个目录独立记忆播放位置，多目录切换不串台 |
| ⚡ 秒开体验 | 纯缓存 + 后台扫描，打开即听 |

## 三、技术栈

- 原生 Kotlin，minSdk 21（兼容 Android 5.0+），compileSdk 34
- 前台服务 PlaybackService（foregroundServiceType="mediaPlayback"）
- 本地存储 SharedPreferences + 缓存文件（.dir 结构缓存 + 按目录 uri hash 存歌曲列表）

## 四、构建与安装

```bash
# 环境变量
export JAVA_HOME="C:/Users/Administrator/dev/java/jdk-17.0.14+7"
export ANDROID_HOME="C:/Users/Administrator/dev/android"
export ANDROID_SDK_ROOT="C:/Users/Administrator/dev/android"

# 编译
gradle assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
