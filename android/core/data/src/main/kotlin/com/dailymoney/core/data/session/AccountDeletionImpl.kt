package com.dailymoney.core.data.session

import com.dailymoney.core.domain.auth.AccountDeletion
import com.dailymoney.core.domain.auth.DeleteAccountResult
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.network.DailyMoneyApi
import com.dailymoney.core.network.model.ProblemDto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * With a backend, the server leaves the user's groups, anonymises them and deletes the Firebase user
 * (Admin SDK, no fresh sign-in needed). Without one there is nothing on a server, so the Firebase user
 * is deleted from the phone.
 */
@Singleton
class AccountDeletionImpl @Inject constructor(
    private val config: AppConfig,
    private val api: DailyMoneyApi,
    private val auth: FirebaseAuth,
    private val json: Json,
) : AccountDeletion {

    override suspend fun delete(): DeleteAccountResult = withContext(Dispatchers.IO) {
        try {
            if (config.backendEnabled) viaServer() else viaFirebase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            DeleteAccountResult.SignInAgain
        } catch (e: Exception) {
            DeleteAccountResult.Failed
        }
    }

    private suspend fun viaServer(): DeleteAccountResult {
        val response = api.deleteAccount()
        if (response.isSuccessful) return DeleteAccountResult.Deleted
        val problem = response.errorBody()?.string()?.let { runCatching { json.decodeFromString(ProblemDto.serializer(), it) }.getOrNull() }
        return if (problem?.code == "TRANSFER_OWNERSHIP_FIRST") {
            DeleteAccountResult.TransferOwnershipFirst(problem.detail?.substringAfter(": ", "").orEmpty())
        } else {
            DeleteAccountResult.Failed
        }
    }

    private suspend fun viaFirebase(): DeleteAccountResult {
        val user = auth.currentUser ?: return DeleteAccountResult.Failed
        user.delete().await()
        return DeleteAccountResult.Deleted
    }
}
