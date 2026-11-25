package com.dailymoney.core.network

import com.dailymoney.core.network.model.ApprovalPolicyDto
import com.dailymoney.core.network.model.CreateGroupDto
import com.dailymoney.core.network.model.DecisionRequestDto
import com.dailymoney.core.network.model.CreatedInvitationDto
import com.dailymoney.core.network.model.GroupDetailDto
import com.dailymoney.core.network.model.GroupSummaryDto
import com.dailymoney.core.network.model.InvitationDto
import com.dailymoney.core.network.model.InvitePreviewDto
import com.dailymoney.core.network.model.InviteRequestDto
import com.dailymoney.core.network.model.MemberDto
import com.dailymoney.core.network.model.MemberPermissionsDto
import com.dailymoney.core.network.model.OverridesDto
import com.dailymoney.core.network.model.PermissionMatrixDto
import com.dailymoney.core.network.model.RoleUpdateDto
import com.dailymoney.core.network.model.RoleChangeDto
import com.dailymoney.core.network.model.TransferDto
import com.dailymoney.core.network.model.UpdateGroupDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Organization endpoints (backend OrganizationController). */
interface GroupsApi {
    @GET("api/v1/organizations")
    suspend fun list(): List<GroupSummaryDto>

    @POST("api/v1/organizations")
    suspend fun create(@Body body: CreateGroupDto): GroupDetailDto

    @GET("api/v1/organizations/{id}")
    suspend fun get(@Path("id") id: String): GroupDetailDto

    @PUT("api/v1/organizations/{id}")
    suspend fun update(@Path("id") id: String, @Body body: UpdateGroupDto): GroupDetailDto

    @DELETE("api/v1/organizations/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>

    @GET("api/v1/organizations/{id}/members")
    suspend fun members(@Path("id") id: String): List<MemberDto>

    @PUT("api/v1/organizations/{id}/members/{userId}/role")
    suspend fun changeRole(@Path("id") id: String, @Path("userId") userId: String, @Body body: RoleChangeDto): Response<Unit>

    @DELETE("api/v1/organizations/{id}/members/{userId}")
    suspend fun removeMember(@Path("id") id: String, @Path("userId") userId: String): Response<Unit>

    @POST("api/v1/organizations/{id}/leave")
    suspend fun leave(@Path("id") id: String): Response<Unit>

    @POST("api/v1/organizations/{id}/transfer-ownership")
    suspend fun transfer(@Path("id") id: String, @Body body: TransferDto): Response<Unit>

    @POST("api/v1/organizations/{id}/invitations")
    suspend fun invite(@Path("id") id: String, @Body body: InviteRequestDto): CreatedInvitationDto

    @GET("api/v1/organizations/{id}/invitations")
    suspend fun invitations(@Path("id") id: String): List<InvitationDto>

    @DELETE("api/v1/invitations/{invitationId}")
    suspend fun revoke(@Path("invitationId") invitationId: String): Response<Unit>

    @GET("api/v1/invitations/by-token/{token}")
    suspend fun preview(@Path("token") token: String): InvitePreviewDto

    @POST("api/v1/invitations/by-token/{token}/accept")
    suspend fun accept(@Path("token") token: String): GroupSummaryDto

    @POST("api/v1/invitations/by-token/{token}/decline")
    suspend fun decline(@Path("token") token: String): Response<Unit>

    @GET("api/v1/organizations/{id}/permissions")
    suspend fun permissions(@Path("id") id: String): PermissionMatrixDto

    @PUT("api/v1/organizations/{id}/roles/{roleId}/permissions")
    suspend fun updateRole(@Path("id") id: String, @Path("roleId") roleId: String, @Body body: RoleUpdateDto): Response<Unit>

    @GET("api/v1/organizations/{id}/members/{userId}/permissions")
    suspend fun memberPermissions(@Path("id") id: String, @Path("userId") userId: String): MemberPermissionsDto

    @PUT("api/v1/organizations/{id}/members/{userId}/permissions")
    suspend fun setOverrides(@Path("id") id: String, @Path("userId") userId: String, @Body body: OverridesDto): Response<Unit>

    @GET("api/v1/organizations/{id}/approval-policy")
    suspend fun approvalPolicy(@Path("id") id: String): ApprovalPolicyDto

    @PUT("api/v1/organizations/{id}/approval-policy")
    suspend fun saveApprovalPolicy(@Path("id") id: String, @Body body: ApprovalPolicyDto): ApprovalPolicyDto

    @POST("api/v1/transactions/{id}/approve")
    suspend fun approve(@Path("id") id: String, @Body body: DecisionRequestDto): Response<Unit>

    @POST("api/v1/transactions/{id}/reject")
    suspend fun reject(@Path("id") id: String, @Body body: DecisionRequestDto): Response<Unit>
}
