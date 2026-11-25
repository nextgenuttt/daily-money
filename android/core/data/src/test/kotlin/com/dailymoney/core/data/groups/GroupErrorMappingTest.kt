package com.dailymoney.core.data.groups

import com.dailymoney.core.domain.sharing.SyncScheduler
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupResult
import com.dailymoney.core.domain.groups.InviteLinks
import com.dailymoney.core.network.GroupsApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupErrorMappingTest {

    private val unusedApi = Proxy.newProxyInstance(GroupsApi::class.java.classLoader, arrayOf(GroupsApi::class.java)) { _, _, _ ->
        error("network must not be called")
    } as GroupsApi

    private fun repo(apiUrl: String = "https://api.example.com/") =
        RemoteGroupRepository(unusedApi, AppConfig("a", "1", "", "", apiUrl), Json { ignoreUnknownKeys = true }, NoSync)

    @Test
    fun `problem details codes become domain errors`() {
        val r = repo()
        assertEquals(GroupError.Rule("TRANSFER_OWNERSHIP_FIRST"), r.mapHttp(422, """{"status":422,"code":"TRANSFER_OWNERSHIP_FIRST","detail":"x"}"""))
        assertEquals(GroupError.Rule("INVITE_EXPIRED"), r.mapHttp(410, """{"code":"INVITE_EXPIRED"}"""))
        assertEquals(GroupError.PermissionDenied, r.mapHttp(403, """{"code":"PERMISSION_DENIED"}"""))
        assertEquals(GroupError.NotFound, r.mapHttp(404, """{"code":"NOT_FOUND"}"""))
        assertEquals(GroupError.SessionExpired, r.mapHttp(401, null))
        assertEquals(GroupError.Unknown("HTTP 500"), r.mapHttp(500, "<html>"))
    }

    @Test
    fun `no backend configured means groups are unavailable without any network call`() = runTest {
        assertEquals(GroupResult.Err(GroupError.BackendDisabled), repo(apiUrl = "").list())
    }

    @Test
    fun `invite token is read from a link or pasted alone`() {
        val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"
        assertEquals(token, InviteLinks.tokenFrom("https://dailymoney-app.web.app/invite/$token"))
        assertEquals(token, InviteLinks.tokenFrom("  Join my group: https://x.web.app/invite/$token?utm=wa "))
        assertEquals(token, InviteLinks.tokenFrom(token))
        assertNull(InviteLinks.tokenFrom("https://evil.example.com/../etc"))
        assertNull(InviteLinks.tokenFrom("short"))
    }
}

private object NoSync : SyncScheduler {
    override fun requestSync() = Unit
    override fun schedulePeriodic() = Unit
}
