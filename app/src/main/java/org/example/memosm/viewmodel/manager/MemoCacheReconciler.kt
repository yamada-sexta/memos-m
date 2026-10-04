package org.example.memosm.viewmodel.manager

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.viewmodel.AccountSession
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A missing page entry is only a candidate; only a resource-specific 404 proves deletion. */
class MemoCacheReconciler(
    private val session: AccountSession,
    private val repository: MemoCacheRepository,
    private val onDeleted: (String) -> Unit
) {
    private var job: Job? = null
    private val confirmedNames = mutableSetOf<String>()

    fun cancel() {
        job?.cancel()
        job = null
        confirmedNames.clear()
    }

    /** Pagination may inform an existing pass without starting another cache-wide sweep. */
    fun noteFetched(fetchedNames: Set<String>) {
        if (job?.isActive == true) confirmedNames.addAll(fetchedNames)
    }

    /** Coalesce page/recovery triggers and exclude names confirmed by concurrent list reads. */
    fun start(fetchedNames: Set<String> = emptySet()) {
        val context = session.current?.takeIf { it.networkReady } ?: return
        if (job?.isActive == true) {
            confirmedNames.addAll(fetchedNames)
            return
        }
        confirmedNames.clear()
        confirmedNames.addAll(fetchedNames)
        val expectedApi = context.api
        job = session.readScope.launch(start = CoroutineStart.LAZY) {
            try {
                val candidates = repository.reconciliationCandidates(context.account.id)
                if (candidates.isEmpty()) return@launch
                // Inventory pages confirm hundreds of resources with a single request.
                // Finish BOTH snapshots before checking omissions; partial lists prove nothing.
                val inventoryNames = mutableSetOf<String>()
                for (state in listOf("NORMAL", "ARCHIVED")) {
                    var token: String? = null
                    val tokens = mutableSetOf<String>()
                    do {
                        delay(250) // Reserve most of the server quota for interactive reads/writes.
                        coroutineContext.ensureActive()
                        if (!session.isCurrent(context) || context.api !== expectedApi) return@launch
                        val page = withTimeout(30_000) {
                            expectedApi.listMemos(pageSize = 100, pageToken = token, state = state)
                        }
                        val memos = page.memos ?: throw IOException("Incomplete memo inventory")
                        inventoryNames.addAll(memos.mapNotNull { it.name })
                        token = page.nextPageToken?.takeIf { it.isNotBlank() }
                        if (token != null && !tokens.add(token)) throw IOException("Repeated inventory page token")
                    } while (token != null)
                }
                for (candidate in candidates) {
                    if (candidate.name in inventoryNames || candidate.name in confirmedNames) continue
                    delay(250)
                    coroutineContext.ensureActive()
                    if (!session.isCurrent(context) || context.api !== expectedApi) return@launch
                    if (candidate.name in confirmedNames) continue
                    try {
                        withTimeout(30_000) { expectedApi.getMemo(candidate.name) }
                    } catch (e: TimeoutCancellationException) {
                        throw IOException("Memo existence check timed out", e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpException) {
                        if (e.code() != 404) throw e
                        coroutineContext.ensureActive()
                        if (!session.isCurrent(context) || context.api !== expectedApi ||
                            candidate.name in confirmedNames) continue
                        if (repository.removeMissingMemo(context.account.id, candidate)) {
                            if (session.isCurrent(context)) onDeleted(candidate.name)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stop on transport/auth/server failures rather than treating them as absence.
                Log.w("MemoCacheReconciler", "Reconciliation interrupted; cached memos retained", e)
            }
        }.also { it.start() }
    }
}
