package world.mnr.talk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val FILE_CHOOSER_REQUEST_CODE = 100
    private val PERMISSION_REQUEST_CODE = 101
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionCallback: MediaSessionCompat.Callback
    private var isAudioPlaying = false
    private var currentTitle = "MNR Talk"
    private var currentArtist = "MNR World"
    private var currentCoverArtUrl: String? = null
    private var currentCoverArtBitmap: Bitmap? = null
    private var notificationVisible = false

    private var mediaPlaybackService: MediaPlaybackService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaPlaybackService.LocalBinder
            mediaPlaybackService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            mediaPlaybackService = null
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mnr_talk_playback"
        private const val ACTION_PLAY = "world.mnr.talk.ACTION_PLAY"
        private const val ACTION_PAUSE = "world.mnr.talk.ACTION_PAUSE"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 102
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.parseColor("#292929")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        setupWebView()
        val urlToLoad = intent?.dataString ?: "https://talk.mnr.world"
        webView.loadUrl(urlToLoad)

        addJavaScriptInterface()
        setupMediaSession()
        createNotificationChannel()
        bindToMediaPlaybackService()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
            }

            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Handler(Looper.getMainLooper()).postDelayed({
                        injectAudioDetectionScript()
                    }, 1000)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false

                    return if (shouldOpenInApp(url)) {
                        view?.loadUrl(url)
                        false
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        } catch (e: Exception) {
                        }
                        true
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val resources = request?.resources ?: return

                    val grantedResources = mutableListOf<String>()

                    if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            grantedResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        } else {
                            pendingPermissionRequest = request
                            requestPermissions(
                                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                                PERMISSION_REQUEST_CODE
                            )
                            return
                        }
                    }

                    if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        grantedResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    }

                    if (resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                        grantedResources.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                    }

                    if (grantedResources.isNotEmpty()) {
                        request.grant(grantedResources.toTypedArray())
                    } else {
                        super.onPermissionRequest(request)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    try {
                        val intent = fileChooserParams?.createIntent()
                        try {
                            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                        } catch (e: Exception) {
                            val genericIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            startActivityForResult(genericIntent, FILE_CHOOSER_REQUEST_CODE)
                        }
                    } catch (e: Exception) {
                        fileUploadCallback = null
                        return false
                    }
                    return true
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                try {
                    val request = android.app.DownloadManager.Request(Uri.parse(url))
                    request.setMimeType(mimetype)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Downloading audio...")
                    request.setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype))
                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    )

                    val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    dm.enqueue(request)
                } catch (e: Exception) {
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                var results: Array<Uri>? = null
                if (data?.data != null) {
                    results = arrayOf(data.data!!)
                } else if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    results = Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                fileUploadCallback?.onReceiveValue(results)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingPermissionRequest?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (isAudioPlaying) {
                    showNotification()
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { url ->
            if (shouldOpenInApp(url)) {
                webView.loadUrl(url)
            }
        }
    }

    private fun shouldOpenInApp(url: String): Boolean {
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            if (host == "talk.mnr.world" ||
                host.endsWith(".talk.mnr.world")) {
                return true
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        webView.destroy()
        unbindFromMediaPlaybackService()
    }

    private fun addJavaScriptInterface() {
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onAudioPlay() {
                runOnUiThread { onAudioStarted() }
            }

            @android.webkit.JavascriptInterface
            fun onAudioPause() {
                runOnUiThread { onAudioPaused() }
            }

            @android.webkit.JavascriptInterface
            fun onTrackChange(title: String, artist: String, coverArt: String) {
                runOnUiThread {
                    currentTitle = title
                    currentArtist = artist
                    updateMediaMetadata()
                    loadCoverArt(coverArt)

                    if (notificationVisible || isAudioPlaying) {
                        updateNotification()
                    }
                }
            }
        }, "AndroidMediaController")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MNR_Talk_MediaSession")
        mediaSession.isActive = true

        mediaSessionCallback = object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                webView.evaluateJavascript("""
                    (function() {
                        const playerDiv = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                            div.classList.contains('z-50') &&
                            div.classList.contains('border-t') &&
                            (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                        );
                        if (playerDiv) {
                            const playButton = playerDiv.querySelector('svg.lucide-play')?.closest('button');
                            if (playButton) {
                                playButton.click();
                                return true;
                            }
                        }

                        const allAudio = Array.from(document.querySelectorAll('audio, video'));
                        const pausedAudio = allAudio.filter(a => a.paused);
                        if (pausedAudio.length > 0) {
                            pausedAudio[0].play();
                            return true;
                        }
                        return false;
                    })();
                """.trimIndent(), null)

                isAudioPlaying = true
                updatePlaybackState()
                showNotification()
            }

            override fun onPause() {
                webView.evaluateJavascript("""
                    (function() {
                        const playerDiv = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                            div.classList.contains('z-50') &&
                            div.classList.contains('border-t') &&
                            (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                        );
                        if (playerDiv) {
                            const pauseButton = playerDiv.querySelector('svg.lucide-pause')?.closest('button');
                            if (pauseButton) {
                                pauseButton.click();
                                return true;
                            }
                        }

                        const allAudio = Array.from(document.querySelectorAll('audio, video'));
                        const playingAudio = allAudio.find(a => !a.paused);
                        if (playingAudio) {
                            playingAudio.pause();
                            return true;
                        }
                        return false;
                    })();
                """.trimIndent(), null)

                isAudioPlaying = false
                updatePlaybackState()
                updateNotification()
            }

            override fun onSkipToNext() {
                webView.evaluateJavascript("""
                    (function() {
                        const playerDiv = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                            div.classList.contains('z-50') &&
                            div.classList.contains('border-t') &&
                            (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                        );
                        if (playerDiv) {
                            const nextButton = playerDiv.querySelector('svg.lucide-skip-forward')?.closest('button');
                            if (nextButton) {
                                nextButton.click();
                                return true;
                            }
                        }
                        return false;
                    })();
                """.trimIndent(), null)
            }

            override fun onSkipToPrevious() {
                webView.evaluateJavascript("""
                    (function() {
                        const playerDiv = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                            div.classList.contains('z-50') &&
                            div.classList.contains('border-t') &&
                            (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                        );
                        if (playerDiv) {
                            const prevButton = playerDiv.querySelector('svg.lucide-skip-back')?.closest('button');
                            if (prevButton) {
                                prevButton.click();
                                return true;
                            }
                        }
                        return false;
                    })();
                """.trimIndent(), null)
            }
        }

        mediaSession.setCallback(mediaSessionCallback)
        updatePlaybackState()
        updateMediaMetadata()

        MediaButtonReceiver.setToken(mediaSession.sessionToken, this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updatePlaybackState() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )

        if (isAudioPlaying) {
            stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        } else {
            stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
        }

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "MNR Talk")
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
                return
            }
        }
        updateNotification()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = NotificationManagerCompat.from(this)

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            notificationVisible = true
        } catch (e: SecurityException) {
        }
    }

    private fun hideNotification() {
        if (!notificationVisible) return
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(NOTIFICATION_ID)
        notificationVisible = false
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isAudioPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PLAY)
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isAudioPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                createMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                createMediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT)
            )
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken))

        currentCoverArtBitmap?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun createMediaButtonPendingIntent(keyCode: Int): PendingIntent {
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        mediaButtonIntent.setPackage(packageName)

        return PendingIntent.getBroadcast(
            this, keyCode, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun onAudioStarted() {
        isAudioPlaying = true
        updatePlaybackState()
        showNotification()
        startForegroundServiceIfReady()
    }

    private fun onAudioPaused() {
        isAudioPlaying = false
        updatePlaybackState()
        updateNotification()
        stopForegroundService()
    }

    private fun loadCoverArt(url: String?) {
        if (url.isNullOrEmpty() || url == currentCoverArtUrl) return

        currentCoverArtUrl = url

        thread {
            try {
                val imageUrl = URL(url)
                val bitmap = BitmapFactory.decodeStream(imageUrl.openStream())

                val maxSize = 512
                val scale = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                    maxSize / Math.min(bitmap.width, bitmap.height).toFloat()
                } else {
                    1f
                }

                currentCoverArtBitmap = if (scale < 1f) {
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    bitmap.recycle()
                    scaledBitmap
                } else {
                    bitmap
                }

                runOnUiThread {
                    if (notificationVisible || isAudioPlaying) {
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun injectAudioDetectionScript() {
        val script = """
            (function() {
                let lastTitle = '';
                let lastArtist = '';
                let lastCoverArt = '';
                let lastPlayingState = null;

                function getAudioState() {
                    const audio = document.querySelector('audio, video');
                    if (!audio) return null;
                    return { playing: !audio.paused };
                }

                function extractMetadata() {
                    try {
                        const playerDiv = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                            div.classList.contains('z-50') &&
                            div.classList.contains('border-t') &&
                            (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                        );
                        if (!playerDiv) return null;

                        const titleEl = playerDiv.querySelector('h3');
                        const title = titleEl?.textContent?.trim() || '';

                        const artistEl = playerDiv.querySelector('a[href*="/artists/"]');
                        const artist = artistEl?.textContent?.trim() || 'MNR World';

                        const imgEl = playerDiv.querySelector('img[alt]');
                        const coverArt = imgEl?.getAttribute('src') || '';

                        return { title, artist, coverArt };
                    } catch (e) {
                        return null;
                    }
                }

                function syncPlayingState() {
                    const audioState = getAudioState();
                    if (audioState && audioState.playing !== lastPlayingState) {
                        lastPlayingState = audioState.playing;
                        if (audioState.playing) {
                            window.AndroidMediaController?.onAudioPlay();
                        } else {
                            window.AndroidMediaController?.onAudioPause();
                        }
                    }
                }

                function updateMetadata() {
                    const metadata = extractMetadata();
                    if (metadata && metadata.title) {
                        if (metadata.title !== lastTitle ||
                            metadata.artist !== lastArtist ||
                            metadata.coverArt !== lastCoverArt) {

                            lastTitle = metadata.title;
                            lastArtist = metadata.artist;
                            lastCoverArt = metadata.coverArt;

                            window.AndroidMediaController?.onTrackChange(
                                metadata.title || 'MNR Talk',
                                metadata.artist || 'MNR World',
                                metadata.coverArt || ''
                            );
                        }
                    }
                }

                function detectAudio() {
                    const audioElements = document.querySelectorAll('audio, video');
                    audioElements.forEach(audio => {
                        audio.removeEventListener('play', onPlay);
                        audio.removeEventListener('playing', onPlaying);
                        audio.removeEventListener('pause', onPause);
                        audio.removeEventListener('ended', onEnded);

                        audio.addEventListener('play', onPlay, false);
                        audio.addEventListener('playing', onPlaying, false);
                        audio.addEventListener('pause', onPause, false);
                        audio.addEventListener('ended', onEnded, false);

                        if (!audio.paused) onPlay();
                    });
                }

                function onPlay() {
                    lastPlayingState = true;
                    updateMetadata();
                    window.AndroidMediaController?.onAudioPlay();
                }

                function onPlaying() {
                    lastPlayingState = true;
                    updateMetadata();
                    window.AndroidMediaController?.onAudioPlay();
                }

                function onPause() {
                    lastPlayingState = false;
                    window.AndroidMediaController?.onAudioPause();
                }

                function onEnded() {
                    lastPlayingState = false;
                    window.AndroidMediaController?.onAudioPause();
                }

                detectAudio();
                updateMetadata();
                syncPlayingState();

                setInterval(() => {
                    syncPlayingState();
                    updateMetadata();
                }, 1000);

                new MutationObserver(() => {
                    detectAudio();
                    updateMetadata();
                }).observe(document.body, { childList: true, subtree: true });

                setTimeout(() => {
                    const player = Array.from(document.querySelectorAll('div.fixed')).find(div =>
                        div.classList.contains('z-50') &&
                        div.classList.contains('border-t') &&
                        (div.classList.contains('bottom-16') || div.classList.contains('md:bottom-0'))
                    );
                    if (player) {
                        new MutationObserver(() => syncPlayingState()).observe(player, {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: ['class']
                        });
                    }
                }, 2000);

                window.addEventListener('playbackStarted', onPlay);
                window.addEventListener('playbackPaused', onPause);
                window.addEventListener('trackChanged', (e) => {
                    if (e.detail) {
                        lastTitle = e.detail.title || lastTitle;
                        lastArtist = e.detail.artist || lastArtist;
                        lastCoverArt = e.detail.coverArt || lastCoverArt;
                        window.AndroidMediaController?.onTrackChange(
                            e.detail.title || 'Unknown Track',
                            e.detail.artist || 'Unknown Artist',
                            e.detail.coverArt || ''
                        );
                    }
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun bindToMediaPlaybackService() {
        Intent(this, MediaPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindFromMediaPlaybackService() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            mediaPlaybackService = null
        }
    }

    private fun startForegroundServiceIfReady() {
        val notification = createNotification()
        mediaPlaybackService?.startForeground(notification)
    }

    private fun stopForegroundService() {
        mediaPlaybackService?.stopForegroundService()
    }
}
