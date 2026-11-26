package com.embeddablechatbot.android.feature.auth

import androidx.lifecycle.SavedStateHandle
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.repository.*
import com.embeddablechatbot.android.core.domain.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelsTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun cleanup(){Dispatchers.resetMain()}
    private class Registration : RegistrationRepository {
        var calls=0;var resends=0;var gate:CompletableDeferred<Unit>?=null
        var result:Outcome<RegistrationStatus> = Outcome.Ok(RegistrationStatus.ConfirmEmail)
        override suspend fun register(email:String,password:String):Outcome<RegistrationStatus>{calls++;gate?.await();return result}
        override suspend fun resendConfirmation(email:String):Outcome<Unit>{resends++;return Outcome.Ok(Unit)}
    }
    private fun valid(vm:RegistrationViewModel){vm.email("new@example.test");vm.password("a-long-test-password");vm.confirmation("a-long-test-password")}
    @Test fun `invalid input never calls registration`()=runTest(dispatcher){
        val repo=Registration();val vm=RegistrationViewModel(repo,SavedStateHandle())
        vm.email("invalid");vm.password("short");vm.register();runCurrent()
        assertEquals(0,repo.calls);assertTrue(vm.state.value.error is AppError.Invalid)
        valid(vm);vm.confirmation("different-password");vm.register();runCurrent();assertEquals(0,repo.calls)
    }
    @Test fun `confirmation persists email and stage but clears both password fields`()=runTest(dispatcher){
        val saved=SavedStateHandle();val repo=Registration();val vm=RegistrationViewModel(repo,saved)
        valid(vm);vm.register();runCurrent()
        assertTrue(vm.state.value.checkEmail);assertEquals("",vm.state.value.password);assertEquals("",vm.state.value.confirmation)
        assertTrue(saved.keys().none{it.contains("password",true)})
        val restored=RegistrationViewModel(repo,saved);assertTrue(restored.state.value.checkEmail);assertEquals("new@example.test",restored.state.value.email)
        advanceTimeBy(61_000);runCurrent()
    }
    @Test fun `busy registration blocks duplicate taps and errors allow retry`()=runTest(dispatcher){
        val repo=Registration().apply{gate=CompletableDeferred();result=Outcome.Err(AppError.Network(false))};val vm=RegistrationViewModel(repo,SavedStateHandle())
        valid(vm);vm.register();vm.register();runCurrent();assertEquals(1,repo.calls)
        repo.gate!!.complete(Unit);runCurrent();assertFalse(vm.state.value.busy)
        repo.result=Outcome.Ok(RegistrationStatus.SignedIn);vm.register();runCurrent();assertEquals(2,repo.calls);assertFalse(vm.state.value.checkEmail);assertEquals("",vm.state.value.password)
    }
    @Test fun `resend has a cooldown and does not repeat registration`()=runTest(dispatcher){
        val repo=Registration();val vm=RegistrationViewModel(repo,SavedStateHandle());valid(vm);vm.register();runCurrent()
        vm.resend();runCurrent();assertEquals(0,repo.resends)
        advanceTimeBy(60_000);runCurrent();vm.resend();vm.resend();runCurrent()
        assertEquals(1,repo.resends);assertEquals(1,repo.calls);assertTrue(vm.state.value.resent)
        advanceTimeBy(61_000);runCurrent()
    }
    @Test fun `new account without Gmail is routed to connection even if another account finished onboarding`()=runTest(dispatcher){
        val mail=FakeMailRepository().apply{statusResult=Outcome.Ok(mailStatus(connected=false))}
        val vm=OnboardingViewModel(object:OnboardingRepository{override suspend fun prepare()=Outcome.Ok(Unit)},mail,FakeConfigRepository(),FakeAuthRepository(),FakePreferencesRepository(UserPreferences(onboardedAccounts=setOf("other-user"))))
        runCurrent();assertTrue(vm.state.value.ready);assertFalse(vm.state.value.connected);assertFalse(vm.state.value.previouslyOnboarded)
    }
    @Test fun `connected returning account proceeds and server errors remain retryable`()=runTest(dispatcher){
        var result:Outcome<Unit> = Outcome.Err(AppError.Network(false))
        val vm=OnboardingViewModel(object:OnboardingRepository{override suspend fun prepare()=result},FakeMailRepository(),FakeConfigRepository(),FakeAuthRepository(),FakePreferencesRepository())
        runCurrent();assertFalse(vm.state.value.ready);assertTrue(vm.state.value.error is AppError.Network)
        result=Outcome.Ok(Unit);vm.prepare();runCurrent();assertTrue(vm.state.value.ready);assertTrue(vm.state.value.connected)
    }
    @Test fun `offline, an account set up on this phone opens anyway`()=runTest(dispatcher){
        val vm=OnboardingViewModel(object:OnboardingRepository{override suspend fun prepare():Outcome<Unit> =Outcome.Err(AppError.Network(false))},FakeMailRepository(),FakeConfigRepository(),FakeAuthRepository(),FakePreferencesRepository(UserPreferences(onboardedAccounts=setOf("user-1"))))
        runCurrent();assertTrue(vm.state.value.ready);assertTrue(vm.state.value.previouslyOnboarded);assertNull(vm.state.value.error)
    }
    @Test fun `server errors still block startup even for a set-up account`()=runTest(dispatcher){
        val vm=OnboardingViewModel(object:OnboardingRepository{override suspend fun prepare():Outcome<Unit> =Outcome.Err(AppError.Server(500,"x"))},FakeMailRepository(),FakeConfigRepository(),FakeAuthRepository(),FakePreferencesRepository(UserPreferences(onboardedAccounts=setOf("user-1"))))
        runCurrent();assertFalse(vm.state.value.ready);assertTrue(vm.state.value.error is AppError.Server)
    }
}
