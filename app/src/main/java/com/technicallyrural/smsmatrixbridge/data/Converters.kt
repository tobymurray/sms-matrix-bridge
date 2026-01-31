package com.technicallyrural.smsmatrixbridge.data

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @TypeConverter
    fun fromMessageDirection(direction: MessageDirection): String = direction.name

    @TypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromMessageOrigin(origin: MessageOrigin): String = origin.name

    @TypeConverter
    fun toMessageOrigin(value: String): MessageOrigin = MessageOrigin.valueOf(value)

    @TypeConverter
    fun fromSyncDirection(direction: SyncDirection): String = direction.name

    @TypeConverter
    fun toSyncDirection(value: String): SyncDirection = SyncDirection.valueOf(value)
}
