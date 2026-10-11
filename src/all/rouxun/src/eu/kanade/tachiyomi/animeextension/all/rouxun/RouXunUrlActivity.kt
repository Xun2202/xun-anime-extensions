package eu.kanade.tachiyomi.animeextension.all.rouxun

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://rou.video/v/<id>, /s/<id> and /t/<tag> links and hands
 * them to the app's global search, where [RouXun] resolves them.
 */
class RouXunUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", intent.data.toString())
            putExtra("filter", packageName)
        }

        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(javaClass.simpleName, "Unable to launch activity", e)
        }

        finish()
        exitProcess(0)
    }
}
