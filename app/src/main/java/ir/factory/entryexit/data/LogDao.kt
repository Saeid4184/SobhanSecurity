package ir.factory.entryexit.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LogDao {

    /** Used only by the Firestore sync mirror: insert-or-replace a document snapshot as-is. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: LogEntity)

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM logs WHERE personId = :personId ORDER BY timestamp DESC")
    fun getLogsForPerson(personId: String): LiveData<List<LogEntity>>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): LiveData<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentByType(type: String, limit: Int = 10): LiveData<List<LogEntity>>
}
