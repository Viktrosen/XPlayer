/*
 * Copyright 2018 Google Inc. All rights reserved.
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_DEFERRED
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.android.uamp.media.extensions.isPlayEnabled
import com.example.android.uamp.media.extensions.isPlaying
import com.example.android.uamp.media.extensions.isSkipToNextEnabled
import com.example.android.uamp.media.extensions.isSkipToPreviousEnabled

const val NOW_PLAYING_CHANNEL: String = "com.example.android.uamp.media.NOW_PLAYING"
const val NOW_PLAYING_NOTIFICATION: Int = 0xb339

/**
 * Helper class to encapsulate code for building notifications.
 */
class NotificationBuilder(private val context: Context) {
    private val platformNotificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val rewindAction = NotificationCompat.Action(
        R.drawable.player_icon_rewind,
        context.getString(R.string.notification_rewind),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_REWIND)
    )

    private val fastForwardAction = NotificationCompat.Action(
        R.drawable.player_icon_forward,
        context.getString(R.string.notification_fast_forward),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_FAST_FORWARD)
    )

    private val skipToPreviousAction = NotificationCompat.Action(
        R.drawable.player_icon_previous,
        context.getString(R.string.notification_skip_to_previous),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_SKIP_TO_PREVIOUS)
    )
    private val playAction = NotificationCompat.Action(
        R.drawable.player_icon_play,
        context.getString(R.string.notification_play),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_PLAY)
    )
    private val pauseAction = NotificationCompat.Action(
        R.drawable.player_icon_pause,
        context.getString(R.string.notification_pause),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_PAUSE)
    )
    private val skipToNextAction = NotificationCompat.Action(
        R.drawable.player_icon_next,
        context.getString(R.string.notification_skip_to_next),
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_SKIP_TO_NEXT)
    )
    private val stopPendingIntent =
        MediaButtonReceiver.buildMediaButtonPendingIntent(context, ACTION_STOP)

    fun buildNotification(sessionToken: MediaSessionCompat.Token): Notification {
        if (shouldCreateNowPlayingChannel()) {
            createNowPlayingChannel()
        }

        val controller = MediaControllerCompat(context, sessionToken)
        val description = controller.metadata.description
        val playbackState = controller.playbackState

        val builder = NotificationCompat.Builder(context, NOW_PLAYING_CHANNEL)
        val mediaStyle = MediaStyle()
            .setCancelButtonIntent(stopPendingIntent)
            .setShowActionsInCompactView(1,2,3)
            .setMediaSession(sessionToken)
            .setShowCancelButton(true)

        // Only add actions for skip back, play/pause, skip forward, based on what's enabled.
        var playPauseIndex = 0
        builder.addAction(rewindAction)
        if (playbackState.isSkipToPreviousEnabled) {
            builder.addAction(skipToPreviousAction)
            ++playPauseIndex
        } else mediaStyle.setShowActionsInCompactView(2,3)
        if (playbackState.isPlaying) {
            builder.addAction(pauseAction)
        } else if (playbackState.isPlayEnabled) {
            builder.addAction(playAction)
        }
        if (playbackState.isSkipToNextEnabled) {
            builder.addAction(skipToNextAction)
        } else mediaStyle.setShowActionsInCompactView(1,2)
        builder.addAction(fastForwardAction)
        if (shouldSetForeground())
            builder.foregroundServiceBehavior = FOREGROUND_SERVICE_IMMEDIATE

        return builder.setContentIntent(controller.sessionActivity)
            .setContentText(description.subtitle)
            .setContentTitle(description.title)
            .setDeleteIntent(stopPendingIntent)
            .setLargeIcon(description.iconBitmap)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.player_icon_notification)
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun shouldCreateNowPlayingChannel() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !nowPlayingChannelExists()

    private fun shouldSetForeground() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S


    @RequiresApi(Build.VERSION_CODES.O)
    private fun nowPlayingChannelExists() =
        platformNotificationManager.getNotificationChannel(NOW_PLAYING_CHANNEL) != null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNowPlayingChannel() {
        val notificationChannel = NotificationChannel(
            NOW_PLAYING_CHANNEL,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
            .apply {
                description = context.getString(R.string.notification_channel_description)
            }

        platformNotificationManager.createNotificationChannel(notificationChannel)
    }
}

