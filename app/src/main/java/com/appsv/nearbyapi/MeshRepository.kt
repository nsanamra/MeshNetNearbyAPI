package com.appsv.nearbyapi

import androidx.lifecycle.MutableLiveData

/**
 * Singleton to hold the shared state of active members.
 * This allows MainActivity to update the list and MembersActivity to observe it.
 */
object MeshRepository {
    // A map of UserId -> MemberInfo
    private val membersMap = mutableMapOf<String, MeshMember>()

    // LiveData so the Activity updates automatically
    val activeMembers = MutableLiveData<List<MeshMember>>()

    fun updateMember(id: String, name: String, timestamp: Long) {
        synchronized(this) {
            membersMap[id] = MeshMember(id, name, timestamp)
            postUpdate()
        }
    }

    fun removeMember(id: String) {
        synchronized(this) {
            if (membersMap.containsKey(id)) {
                membersMap.remove(id)
                postUpdate()
            }
        }
    }

    fun pruneExpiredMembers(timeoutMillis: Long) {
        synchronized(this) {
            val currentTime = System.currentTimeMillis()
            val iterator = membersMap.entries.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                // Don't remove "You" (the local user)
                if (entry.value.name != "You" && (currentTime - entry.value.lastSeen) > timeoutMillis) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) postUpdate()
        }
    }

    private fun postUpdate() {
        // Sort by name for a clean list
        activeMembers.postValue(membersMap.values.sortedBy { it.name })
    }
}

data class MeshMember(
    val id: String,
    val name: String,
    val lastSeen: Long
)