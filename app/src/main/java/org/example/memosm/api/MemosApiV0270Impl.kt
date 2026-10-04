package org.example.memosm.api

import org.example.memosm.model.CurrentSessionResponse
import org.example.memosm.model.ListUsersResponse
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInResponse
import org.example.memosm.model.User
import org.example.memosm.model.toUserSnapshot

open class MemosApiV0270Impl(
    private val apiV0270: MemosApiV0270
) : MemosApiV0260Impl(apiV0270) {
    override val constants = super.constants.copy(
        memoCreatorFilterStyle = MemoCreatorFilterStyle.RESOURCE_NAME,
        memoOrderByPinnedDesc = "pinned desc, display_time desc",
        memoOrderByNewest = "display_time desc",
        memoOrderByOldest = "display_time asc"
    )

    override suspend fun getCurrentSession(): CurrentSessionResponse {
        return apiV0270.getCurrentUserV0270().toModel()
    }

    override suspend fun signIn(request: SignInRequest): SignInResponse {
        return apiV0270.signInV0270(request).toModel()
    }

    override suspend fun listUsers(
        pageSize: Int?,
        pageToken: String?,
        filter: String?,
        showDeleted: Boolean?
    ): ListUsersResponse {
        return apiV0270.listUsersV0270(pageSize, pageToken, filter, showDeleted).toModel()
    }

    override suspend fun createUser(
        user: User,
        userId: String?,
        validateOnly: Boolean?,
        requestId: String?
    ): User {
        return apiV0270.createUserV0270(user.toUserSnapshot(), userId, validateOnly, requestId)
    }

    override suspend fun getUser(user: String, readMask: String?): User {
        return apiV0270.getUserV0270(user, readMask)
    }

    override suspend fun updateUser(
        user: String,
        userData: User,
        updateMask: String,
        allowMissing: Boolean?
    ): User {
        return apiV0270.updateUserV0270(user, userData.toUserSnapshot(), updateMask, allowMissing)
    }
}
