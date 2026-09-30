package org.example.memosm.api

import org.example.memosm.model.CurrentSessionResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInRequestV0260
import org.example.memosm.model.SignInResponse

open class MemosApiV0260Impl(
    private val apiV0260: MemosApiV0260
) : MemosApiImpl(apiV0260) {

    override val constants = super.constants.copy(
        memoCreatorFilterStyle = MemoCreatorFilterStyle.LEGACY_ID,
        memoOrderByPinnedDesc = "pinned desc, display_time desc",
        memoOrderByNewest = "display_time desc",
        memoOrderByOldest = "display_time asc"
    )

    override suspend fun getCurrentSession(): CurrentSessionResponse {
        // v0.26.0+ uses auth/me instead of auth/sessions/current
        val userResponse = apiV0260.getCurrentUser()
        return CurrentSessionResponse(user = userResponse.user)
    }

    override suspend fun createMemo(memo: Memo, memoId: String?): Memo {
        // v0.26/v0.27 servers reject the memoId parameter, so the idempotency
        // key is dropped here (v0.28+ forwards it via MemosApiV0280Impl).
        return super.createMemo(memo, null)
    }

    override suspend fun createMemoComment(memo: String, comment: Memo, commentId: String?): Memo {
        // Same as memoId above: v0.26/v0.27 reject the commentId parameter, so
        // the idempotency key is dropped here (v0.28+ forwards it).
        return super.createMemoComment(memo, comment, null)
    }

    override suspend fun signIn(request: SignInRequest): SignInResponse {
        // v0.26.0+ uses different structure for signin
        val v0260Request = SignInRequestV0260(
            passwordCredentials = request.passwordCredentials
        )
        return apiV0260.signIn(v0260Request).toModel()
    }
}
