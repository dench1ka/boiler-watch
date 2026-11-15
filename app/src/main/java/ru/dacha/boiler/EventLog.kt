package ru.dacha.boiler

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    fun stamp(time: Long = System.currentTimeMillis()): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss Z", Locale.getDefault()).format(Date(time))
    @Synchronized fun add(context: Context, message: String) {
        try {
            val f = File(context.filesDir, "events.txt")
            val old = if (f.exists()) f.readText().takeLast(24000) else ""
            f.writeText((old + stamp() + "  " + message + "\n").takeLast(26000))
        } catch (_: IOException) {
            Log.w("BoilerWatch", "Event log storage unavailable")
        } catch (_: SecurityException) {
            Log.w("BoilerWatch", "Event log storage denied")
        }
    }
    @Synchronized fun read(context: Context): String = try {
        File(context.filesDir, "events.txt").let { if (it.exists()) it.readText() else "Событий пока нет" }
    } catch (_: IOException) { "Журнал временно недоступен" }
      catch (_: SecurityException) { "Доступ к журналу запрещён" }
}
