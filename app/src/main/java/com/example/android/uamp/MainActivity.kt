package com.example.android.uamp

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.AsyncTask
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.example.android.uamp.media.IPlayer
import com.example.android.uamp.media.Player
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.URL

class MainActivity : AppCompatActivity() {
    private val log: TextView by lazy { findViewById<TextView>(R.id.logs) }
    private lateinit var counter: CountDownTimer
    private val player by lazy {
        Player.init(application)
        Player
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        player.liveDataPlayerState.observe(this, Observer {
            log.appendLine("State", it)
        })
        player.liveDataPlayNow.observe(this, Observer {
            log.appendLine("PlayNow", it)
        })
        player.liveDataPlayList.observe(this, Observer {
            log.appendLine("PlayList", it.joinToString(separator = "\n\n", prefix = "\n"))
        })
        counter = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onFinish() {
            }

            override fun onTick(millisUntilFinished: Long) {
                GlobalScope.launch {
                    log.appendLine(
                            "progress",
                            "${player.currentPosition}/${player.trackDuration.await()}, speed=${player.speed}"
                    )
                }
            }

        }
        counter.start()
        if (player.playList == null) {
            AsyncTask.THREAD_POOL_EXECUTOR.execute {
                val artUri =
                    "https://storage.googleapis.com/uamp/The_Kyoto_Connection_-_Wake_Up/art.jpg"
                val art = BitmapFactory.decodeStream(URL(artUri).openStream())
                /*IPlayer.Track(
                        "id_1",
                        Uri.fromFile(File("storage/emulated/0/Music/Imagine Dragons - I'm So Sorry.mp3")).path ?: "",
                        "Intro - The Way Of Waking Up (feat. Alan Watts)",
                        "The Kyoto Connection",
                        "Wake Up",
                        artUri,
                        (getDrawable(com.example.android.uamp.media.R.drawable.player_default_art) as BitmapDrawable).bitmap
                    )*/
                player.playList = listOf(
                    IPlayer.Track(
                        "id_1",
                        "https://storage.googleapis.com/uamp/The_Kyoto_Connection_-_Wake_Up/01_-_Intro_-_The_Way_Of_Waking_Up_feat_Alan_Watts.mp3",
                        "Intro - The Way Of Waking Up (feat. Alan Watts)",
                        "The Kyoto Connection",
                        "Wake Up",
                        artUri,
                        (getDrawable(com.example.android.uamp.media.R.drawable.player_default_art) as BitmapDrawable).bitmap
                    ),
                    IPlayer.Track(
                        "id_2",
                        "https://storage.googleapis.com/uamp/The_Kyoto_Connection_-_Wake_Up/02_-_Geisha.mp3",
                        "Geisha",
                        "The Kyoto Connection",
                        "Wake Up",
                        artUri,
                        art
                    ),
                    IPlayer.Track(
                        "id_3",
                        "https://storage.googleapis.com/uamp/The_Kyoto_Connection_-_Wake_Up/10_-_Wake_Up.mp3",
                        "Wake Up",
                        "The Kyoto Connection",
                        "Wake Up",
                        artUri,
                        art
                    )
                )
            }
        }
        toggle.setOnClickListener { player.togglePlayPause() }
        prev.setOnClickListener { player.prev() }
        next.setOnClickListener { player.next() }
        stop.setOnClickListener { player.stop() }
        speed.setOnClickListener { player.speed = if (player.speed == 1F) 2F else 1f }
        revert.setOnClickListener { player.playList = player.playList?.reversed() }
    }

    override fun onDestroy() {
        counter.cancel()
        super.onDestroy()
    }

    private fun TextView.appendLine(key: String? = null, value: Any? = null) {
        val runnable = Runnable {
            val line = (key?.plus(": ") ?: "").plus(value.toString())
            append("\n$line")
            scrollView.scrollBy(0, 100)
        }
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            runnable.run()
        } else {
            post(runnable)
        }
    }
}


