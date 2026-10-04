package org.example.memosm.viewmodel.manager

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                // Batches bound both concurrent requests and coroutine allocation for large caches.
                for (batch in candidates.chunked(4)) {
                    coroutineContext.ensureActive()
                    if (!session.isCurrent(context) || context.api !== expectedApi) return@launch
                    coroutineScope {
                        batch.map { candidate -> async {
                            if (candidate.name in confirmedNames) return@async
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
                                    candidate.name in confirmedNames) return@async
                                if (repository.removeMissingMemo(context.account.id, candidate)) {
                                    if (session.isCurrent(context)) onDeleted(candidate.name)
                                }
                            }
                        } }.awaitAll()
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
