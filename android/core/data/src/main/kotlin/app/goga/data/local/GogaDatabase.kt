package app.goga.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class,
        TaskEntity::class,
        ReminderEntity::class,
        PendingCommandEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GogaDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun tasks(): TaskDao
    abstract fun reminders(): ReminderDao
    abstract fun pendingCommands(): PendingCommandDao

    companion object {
        fun create(context: Context): GogaDatabase =
            Room.databaseBuilder(context, GogaDatabase::class.java, "goga.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
