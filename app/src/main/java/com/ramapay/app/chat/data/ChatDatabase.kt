package com.ramapay.app.chat.data

import android.util.Base64
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ramapay.app.chat.data.dao.ContactDao
import com.ramapay.app.chat.data.dao.ConversationDao
import com.ramapay.app.chat.data.dao.GroupDao
import com.ramapay.app.chat.data.dao.MessageDao
import com.ramapay.app.chat.data.entity.ContactEntity
import com.ramapay.app.chat.data.entity.ConversationEntity
import com.ramapay.app.chat.data.entity.GroupEntity
import com.ramapay.app.chat.data.entity.GroupMemberEntity
import com.ramapay.app.chat.data.entity.GroupRole
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus

/**
 * Room database for MumbleChat.
 * 
 * This database is COMPLETELY SEPARATE from the wallet database.
 * It stores all chat-related data including messages, conversations, groups, and contacts.
 */
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ContactEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupDao(): GroupDao
    abstract fun contactDao(): ContactDao
}

/**
 * Migration from version 1 to 2: Add customName field to conversations table
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE conversations ADD COLUMN customName TEXT DEFAULT NULL")
    }
}

/**
 * Type converters for Room database.
 */
class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromByteArray(bytes: ByteArray?): String? {
        return bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    @TypeConverter
    fun toByteArray(string: String?): ByteArray? {
        return string?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromGroupRole(role: GroupRole): String = role.name

    @TypeConverter
    fun toGroupRole(value: String): GroupRole = GroupRole.valueOf(value)

    @TypeConverter
    fun fromStringList(list: List<String>): String = gson.toJson(list)

    @TypeConverter
    fun toStringList(value: String): List<String> = 
        gson.fromJson(value, object : TypeToken<List<String>>() {}.type)
}
