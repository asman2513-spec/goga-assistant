package app.goga.assistant

import android.app.Application
import app.goga.data.local.GogaDatabase

class GogaApplication : Application() {
    lateinit var database: GogaDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = GogaDatabase.create(this)
    }
}
