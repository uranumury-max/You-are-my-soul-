package com.karam.youaremysoul

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.util.UUID

data class SyncPassResult(
    val uploadedEvents: Int,
    val downloadedEvents: Int,
    val moreRemoteEventsAvailable: Boolean
)

/**
 * Bounded synchronization pass over the durable Room outbox and server cursor.
 * Downloaded events remain encrypted in Room; applying them to UI state is a
 * separate conflict-resolution step and is intentionally not implied here.
 */
class WorldSyncCoordinator(context: Context) {
    private val localStore = RoomSyncStore(context.applicationContext)
    private val transport = SupabaseEventTransport()

    suspend fun syncOnce(
        session: AuthenticatedSupabaseUser,
        worldId: UUID
    ): SyncPassResult {
        var uploaded = 0
        val due = localStore.dueEvents().filter { it.event.worldId == worldId }
        for (pending in due) {
            try {
                transport.append(session, pending.event)
                // If deletion fails after the server accepted the event, retrying
                // the same event ID is safe because append_world_event is idempotent.
                localStore.markDelivered(pending.event.eventId)
                uploaded += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SupabaseAuthException) {
                localStore.recordFailure(
                    pending.event.eventId,
                    System.currentTimeMillis(),
                    error.safeCode
                )
                if (error.safeCode in TRANSIENT_OR_AUTH_FAILURES) break
            } catch (_: Exception) {
                localStore.recordFailure(
                    pending.event.eventId,
                    System.currentTimeMillis(),
                    "sync_transport_failure"
                )
            }
        }

        var downloaded = 0
        var cursor = localStore.cursor(worldId)
        var moreAvailable = false
        for (pageIndex in 0 until MAX_DOWNLOAD_PAGES_PER_PASS) {
            val page = transport.fetchPage(session, worldId, cursor)
            localStore.applyRemotePage(worldId, page, cursor)
            downloaded += page.events.size
            cursor = page.nextCursor
            moreAvailable = page.hasMore
            if (!page.hasMore) break
        }

        return SyncPassResult(
            uploadedEvents = uploaded,
            downloadedEvents = downloaded,
            moreRemoteEventsAvailable = moreAvailable
        )
    }

    private companion object {
        const val MAX_DOWNLOAD_PAGES_PER_PASS = 10
        val TRANSIENT_OR_AUTH_FAILURES = setOf(
            "event_authorization_failed",
            "event_rate_limited",
            "event_service_unavailable",
            "event_network_unavailable"
        )
    }
}
