/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.media

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util

/**
 * This class is the entry point for browsing and playback commands from the APP's UI
 * and other apps that wish to play music via UAMP (for example, Android Auto or
 * the Google Assistant).
 *
 * Browsing begins with the method [MusicService.onGetRoot], and continues in
 * the callback [MusicService.onLoadChildren].
 *
 * For more information on implementing a MediaBrowserService,
 * visit [https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice.html](https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice.html).
 */
class MusicService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaController: MediaControllerCompat
    private lateinit var becomingNoisyReceiver: BecomingNoisyReceiver
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationBuilder
    //    private lateinit var mediaSource: MusicSource
    private lateinit var mediaSessionConnector: MediaSessionConnector

//    /**
//     * This must be `by lazy` because the source won't initially be ready.
//     * See [MusicService.onLoadChildren] to see where it's accessed (and first
//     * constructed).
//     */
//    private val browseTree: BrowseTree by lazy {
//        BrowseTree(mediaSource)
//    }

    private var isForegroundService = false

//    private val remoteJsonSource: Uri =
//            Uri.parse("https://storage.googleapis.com/uamp/catalog.json")

    private val _audioAttributes = AudioAttributes.Builder()
        .setContentType(C.CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    // Wrap a SimpleExoPlayer with a decorator to handle audio focus for us.
    private val exoPlayer: SimpleExoPlayer by lazy {
        ExoPlayerFactory.newSimpleInstance(this).apply {
            setAudioAttributes(_audioAttributes, true)
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Build a PendingIntent that can be used to launch the UI.
        val sessionIntent = packageManager?.getLaunchIntentForPackage(packageName)?.apply {
            //https://stackoverflow.com/questions/12074980/bring-application-to-front-after-user-clicks-on-home-button
            `package` = null
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        val sessionActivityPendingIntent =
            sessionIntent?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        // Create a new MediaSession.
        mediaSession = MediaSessionCompat(this, "MusicService")
            .apply {
                setSessionActivity(sessionActivityPendingIntent)
                isActive = true
            }

        /**
         * In order for [MediaBrowserCompat.ConnectionCallback.onConnected] to be called,
         * a [MediaSessionCompat.Token] needs to be set on the [MediaBrowserServiceCompat].
         *
         * It is possible to wait to set the session token, if required for a specific use-case.
         * However, the token *must* be set by the time [MediaBrowserServiceCompat.onGetRoot]
         * returns, or the connection will fail silently. (The system will not even call
         * [MediaBrowserCompat.ConnectionCallback.onConnectionFailed].)
         */
        sessionToken = mediaSession.sessionToken

        // Because ExoPlayer will manage the MediaSession, add the service as a callback for
        // state changes.
        mediaController = MediaControllerCompat(this, mediaSession).also {
            it.registerCallback(MediaControllerCallback())
        }

        notificationBuilder = NotificationBuilder(this)
        notificationManager = NotificationManagerCompat.from(this)

        becomingNoisyReceiver =
            BecomingNoisyReceiver(context = this, sessionToken = mediaSession.sessionToken)

//        mediaSource = JsonSource(context = this, source = remoteJsonSource)
//        mediaSource = com.example.android.uamp.media.Player.instance.mediaSource

        // ExoPlayer will manage the MediaSession for us.
        mediaSessionConnector = MediaSessionConnector(mediaSession).also {
            // Produces DataSource instances through which media data is loaded.
            val dataSourceFactory = DefaultDataSourceFactory(
                this, Util.getUserAgent(this, UAMP_USER_AGENT), null
            )

            // Create the PlaybackPreparer of the media session connector.
            val playbackPreparer = UampPlaybackPreparer(
//                mediaSource,
                exoPlayer,
                dataSourceFactory
            )

            it.setPlayer(exoPlayer)
            it.setPlaybackPreparer(playbackPreparer)
            it.setQueueNavigator(UampQueueNavigator(mediaSession))
        }
    }

    /**
     * This is the code that causes UAMP to stop playing when swiping it away from recents.
     * The choice to do this is app specific. Some apps stop playback, while others allow playback
     * to continue and allow uses to stop it with the notification.
     */
    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)

        /**
         * By stopping playback, the player will transition to [Player.STATE_IDLE]. This will
         * cause a state change in the MediaSession, and (most importantly) call
         * [MediaControllerCallback.onPlaybackStateChanged]. Because the playback state will
         * be reported as [PlaybackStateCompat.STATE_NONE], the service will first remove
         * itself as a foreground service, and will then call [stopSelf].
         */
        exoPlayer.stop(true)
    }

    override fun onDestroy() {
        mediaSession.run {
            isActive = false
            release()
        }
    }

    /**
     * Returns the "root" media ID that the client should request to get the list of
     * [MediaItem]s to browse/play.
     *
     * TODO: Allow different roots based on which app is attempting to connect.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): MediaBrowserServiceCompat.BrowserRoot? {
        return BrowserRoot("Player", null)
//        return BrowserRoot(UAMP_BROWSABLE_ROOT, null)
    }

    /**
     * Returns (via the [result] parameter) a list of [MediaItem]s that are child
     * items of the provided [parentMediaId]. See [BrowseTree] for more details on
     * how this is build/more details about the relationships.
     */
    override fun onLoadChildren(
        parentMediaId: String,
        result: MediaBrowserServiceCompat.Result<List<MediaItem>>
    ) {
        Log.d("rom", "onLoadChildren: $parentMediaId")
        // If the media source is ready, the results will be set synchronously here.
        /*val resultsSent = mediaSource.whenReady { successfullyInitialized ->
            if (successfullyInitialized) {
//                val children = browseTree[parentMediaId]?.map { item ->
//                    MediaItem(item.description, item.flag)
//                }
                val children = mediaSource.map { item ->
                    MediaItem(item.description, item.flag)
                }*/
        val children = com.example.android.uamp.media.Player.instance.mediaItems
        result.sendResult(children)
        Log.d("rom", "sendResult: $children")
           /* } else {
                result.sendError(null)
            }
        }*/

        // If the results are not ready, the service must "detach" the results before
        // the method returns. After the source is ready, the lambda above will run,
        // and the caller will be notified that the results are ready.
        //
        // See [MediaItemFragmentViewModel.subscriptionCallback] for how this is passed to the
        // UI/displayed in the [RecyclerView].
        // if (!resultsSent) {
        //     result.detach()
        // }
    }

    /**
     * Removes the [NOW_PLAYING_NOTIFICATION] notification.
     *
     * Since `stopForeground(false)` was already called (see
     * [MediaControllerCallback.onPlaybackStateChanged], it's possible to cancel the notification
     * with `notificationManager.cancel(NOW_PLAYING_NOTIFICATION)` if minSdkVersion is >=
     * [Build.VERSION_CODES.LOLLIPOP].
     *
     * Prior to [Build.VERSION_CODES.LOLLIPOP], notifications associated with a foreground
     * service remained marked as "ongoing" even after calling [Service.stopForeground],
     * and cannot be cancelled normally.
     *
     * Fortunately, it's possible to simply call [Service.stopForeground] a second time, this
     * time with `true`. This won't change anything about the service's state, but will simply
     * remove the notification.
     */
    private fun removeNowPlayingNotification() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    /**
     * Class to receive callbacks about state changes to the [MediaSessionCompat]. In response
     * to those callbacks, this class:
     *
     * - Build/update the service's notification.
     * - Register/unregister a broadcast receiver for [AudioManager.ACTION_AUDIO_BECOMING_NOISY].
     * - Calls [Service.startForeground] and [Service.stopForeground].
     */
    private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            mediaController.playbackState?.also { updateNotification(it) }
        }


        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            state?.also { updateNotification(it) }
        }

        private fun updateNotification(state: PlaybackStateCompat) {
            val updatedState = state.state
            if (mediaController.metadata == null) {
                if (updatedState == PlaybackStateCompat.STATE_NONE) {//stop state
                    isForegroundService = false
                    removeNowPlayingNotification()
                }
                return
            }

            // Skip building a notification when state is "none".
            val notification = if (updatedState != PlaybackStateCompat.STATE_NONE) {
                notificationBuilder.buildNotification(mediaSession.sessionToken)
            } else {
                null
            }

            when (updatedState) {
                PlaybackStateCompat.STATE_BUFFERING,
                PlaybackStateCompat.STATE_PLAYING,
                PlaybackStateCompat.STATE_PAUSED -> {
                    becomingNoisyReceiver.register()

                    /**
                     * This may look strange, but the documentation for [Service.startForeground]
                     * notes that "calling this method does *not* put the service in the started
                     * state itself, even though the name sounds like it."
                     */
                    if (!isForegroundService) {
                        ContextCompat.startForegroundService(
                            this@MusicService,
                            Intent(applicationContext, this@MusicService.javaClass)
                        )
                        startForeground(NOW_PLAYING_NOTIFICATION, notification)
                        isForegroundService = true
                    } else if (notification != null) {
                        notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
                    }
                }
                else -> {
                    becomingNoisyReceiver.unregister()

                    if (isForegroundService) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        isForegroundService = false

                        // If playback has ended, also stop the service.
                        if (updatedState == PlaybackStateCompat.STATE_NONE) {
                            stopSelf()
                        }

                        if (notification != null) {
                            notificationManager.notify(NOW_PLAYING_NOTIFICATION, notification)
                        } else {
                            removeNowPlayingNotification()
                        }
                    } else if (updatedState == PlaybackStateCompat.STATE_NONE) {
                        removeNowPlayingNotification()
                    }
                }
            }
        }
    }
}

/**
 * Helper class to retrieve the the Metadata necessary for the ExoPlayer MediaSession connection
 * extension to call [MediaSessionCompat.setMetadata].
 */
private class UampQueueNavigator(
    mediaSession: MediaSessionCompat
) : TimelineQueueNavigator(mediaSession) {
    private val window = Timeline.Window()
    override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat =
        player.currentTimeline
            .getWindow(windowIndex, window, true).tag as MediaDescriptionCompat
}

/**
 * Helper class for listening for when headphones are unplugged (or the audio
 * will otherwise cause playback to become "noisy").
 */
private class BecomingNoisyReceiver(
    private val context: Context,
    sessionToken: MediaSessionCompat.Token
) : BroadcastReceiver() {

    private val noisyIntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    private val controller = MediaControllerCompat(context, sessionToken)

    private var registered = false

    fun register() {
        if (!registered) {
            context.registerReceiver(this, noisyIntentFilter)
            registered = true
        }
    }

    fun unregister() {
        if (registered) {
            context.unregisterReceiver(this)
            registered = false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            controller.transportControls.pause()
        }
    }
}

private const val UAMP_USER_AGENT = "uamp.next"