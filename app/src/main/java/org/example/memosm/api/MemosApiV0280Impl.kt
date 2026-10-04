package org.example.memosm.api

import android.util.Log
import org.example.memosm.model.CurrentSessionResponse
import org.example.memosm.model.ListMemoCommentsResponse
import org.example.memosm.model.ListMemosResponse
import org.example.memosm.model.ListUsersResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInResponse
import org.example.memosm.model.User
import org.example.memosm.model.toUserSnapshot

class MemosApiV0280Impl(
    private val apiV0280: MemosApiV0280
) : MemosApiV0270Impl(apiV0280) {
    override val constants = super.constants.copy(
        memoOrderByPinnedDesc = "pinned desc, create_time desc",
        memoOrderByNewest = "create_time desc",
        memoOrderByOldest = "create_time asc"
    )

    override suspend fun getCurrentSession(): CurrentSessionResponse {
        return apiV0280.getCurrentUserV0280().toModel()
    }

    override suspend fun signIn(request: SignInRequest): SignInResponse {
        return apiV0280.signInV0280(request).toModel()
    }

    override suspend fun listUsers(
        pageSize: Int?,
        pageToken: String?,
        filter: String?,
        showDeleted: Boolean?
    ): ListUsersResponse {
        return apiV0280.listUsersV0280(pageSize, pageToken, filter, showDeleted).toModel()
    }

    override suspend fun createUser(
        user: User,
        userId: String?,
        validateOnly: Boolean?,
        requestId: String?
    ): User {
        return apiV0280.createUserV0280(user.toUserSnapshot(), userId, validateOnly, requestId)
            .normalized()
    }

    override suspend fun getUser(user: String, readMask: String?): User {
        Log.d("MemosApiV0280", "getUser request: user=$user readMask=$readMask")
        val response = apiV0280.getUserV0280(user, readMask).normalized()
        Log.d(
            "MemosApiV0280",
            "getUser response: request=$user name=${response.name} username=${response.username} displayName=${response.displayName} avatarUrl=${response.avatarUrl}"
        )
        if (response.name.isNullOrBlank()) {
            Log.w("MemosApiV0280", "getUser parse: missing name for request=$user")
        }
        if (response.displayName == null && !response.username.isNullOrBlank()) {
            Log.w(
                "MemosApiV0280",
                "getUser parse: blank displayName normalized to null for request=$user, username=${response.username}"
            )
        }
        return response
    }

    override suspend fun updateUser(
        user: String,
        userData: User,
        updateMask: String,
        allowMissing: Boolean?
    ): User {
        return apiV0280.updateUserV0280(user, userData.toUserSnapshot(), updateMask, allowMissing)
            .normalized()
    }

    override suspend fun listMemos(
        pageSize: Int?,
        pageToken: String?,
        state: String?,
        orderBy: String?,
        filter: String?,
        showDeleted: Boolean?
    ): ListMemosResponse {
        return apiV0280.listMemosV0280(
            pageSize = pageSize,
            pageToken = pageToken,
            state = state,
            orderBy = orderBy,
            filter = filter,
            showDeleted = showDeleted
        ).toModel()
    }

    override suspend fun createMemo(memo: Memo, memoId: String?): Memo {
        return apiV0280.createMemoV0280(MemoV0280.fromModel(memo), memoId).toModel()
    }

    override suspend fun getMemo(memo: String): Memo {
        return apiV0280.getMemoV0280(memo).toModel()
    }

    override suspend fun updateMemo(memo: String, memoData: Memo, updateMask: String): Memo {
        return apiV0280.updateMemoV0280(memo, MemoV0280.fromModel(memoData), updateMask).toModel()
    }

    override suspend fun listMemoComments(
        memo: String,
        pageSize: Int?,
        pageToken: String?,
        orderBy: String?
    ): ListMemoCommentsResponse {
        return apiV0280.listMemoCommentsV0280(memo, pageSize, pageToken, orderBy).toModel()
    }

    override suspend fun createMemoComment(memo: String, comment: Memo, commentId: String?): Memo {
        return apiV0280.createMemoCommentV0280(
            memo = memo,
            comment = MemoV0280.fromModel(comment),
            commentId = commentId
        ).toModel()
    }
}
