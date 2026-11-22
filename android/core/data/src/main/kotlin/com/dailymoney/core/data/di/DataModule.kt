package com.dailymoney.core.data.di

import com.dailymoney.core.data.auth.FirebaseAuthRepository
import com.dailymoney.core.data.auth.FirebaseTokenProvider
import com.dailymoney.core.data.session.BackendSessionImpl
import com.dailymoney.core.data.session.LocalDataWiperImpl
import com.dailymoney.core.data.session.PendingChangesRepositoryImpl
import com.dailymoney.core.domain.auth.AuthRepository
import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.auth.LocalDataWiper
import com.dailymoney.core.domain.auth.PendingChangesRepository
import com.dailymoney.core.network.AuthTokenProvider
import com.google.firebase.auth.FirebaseAuth
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun authRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    abstract fun backendSession(impl: BackendSessionImpl): BackendSession

    @Binds
    abstract fun localDataWiper(impl: LocalDataWiperImpl): LocalDataWiper

    @Binds
    abstract fun pendingChanges(impl: PendingChangesRepositoryImpl): PendingChangesRepository

    @Binds
    abstract fun authTokenProvider(impl: FirebaseTokenProvider): AuthTokenProvider

    companion object {
        @Provides
        @Singleton
        fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    }
}
