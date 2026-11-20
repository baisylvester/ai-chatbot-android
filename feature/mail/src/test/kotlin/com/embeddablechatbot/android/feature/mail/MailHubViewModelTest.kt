package com.embeddablechatbot.android.feature.mail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailHubViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val inbox = TestInbox()
    private val mail = FakeMailRepository()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = MailHubViewModel(inbox,mail,FakeConfigRepository(),FakePreferencesRepository(),SequentialIds(),saved)

    @Test fun `server errors remain visible across tabs and do not look like an empty mailbox`() = runTest(dispatcher) {
        mail.statusResult = Outcome.Err(AppError.NotConfigured("Deploy the mail service"))
        val vm = vm(); runCurrent()
        assertFalse(vm.state.value.loaded); assertTrue(vm.state.value.error is AppError.NotConfigured)
        vm.selectTab(HubTab.Writing); vm.selectTab(HubTab.Mail)
        assertTrue(vm.state.value.error is AppError.NotConfigured); assertEquals(0,inbox.lists.size)
    }
    @Test fun `disconnected mailbox never loads mail`() = runTest(dispatcher) {
        mail.statusResult = Outcome.Ok(mailStatus(connected = false))
        val vm = vm(); runCurrent()
        assertFalse(vm.state.value.loading); assertEquals(0,inbox.lists.size)
    }
    @Test fun `pagination deduplicates messages and refresh clears a stale loading flag`() = runTest(dispatcher) {
        inbox.pages += Outcome.Ok(InboxPage(listOf(message("one")),"page-two"))
        inbox.pages += Outcome.Ok(InboxPage(listOf(message("one"),message("two")),null))
        val vm = vm(); runCurrent(); vm.more(); vm.more(); runCurrent()
        assertEquals(listOf("one","two"),vm.state.value.messages.map {it.id})
        assertFalse(vm.state.value.loadingMore); assertEquals(2,inbox.lists.size)
    }
    @Test fun `late folder response cannot replace a newer folder`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Outcome<InboxPage>>()
        inbox.firstPage = gate
        val vm = vm(); runCurrent(); vm.selectFolder(MailFolder.Sent); runCurrent()
        gate.complete(Outcome.Ok(InboxPage(listOf(message("old")),null))); runCurrent()
        assertEquals(MailFolder.Sent,vm.state.value.folder); assertTrue(vm.state.value.messages.isEmpty())
    }
    @Test fun `double tapping save creates one draft then opens the approval`() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); vm.compose("ana@example.test","Hello","Body")
        vm.saveDraft(); vm.saveDraft(); runCurrent()
        assertEquals(1,inbox.drafts.size); assertFalse(vm.composer.value.visible)
        vm.effect.test { assertEquals(HubEffect.Review("action"),awaitItem()) }
    }
    @Test fun `uncertain draft save is preserved after recreation and cannot be resubmitted`() = runTest(dispatcher) {
        val saved=SavedStateHandle(); inbox.draftResult=Outcome.Err(AppError.Network(true))
        val vm=vm(saved); runCurrent(); vm.compose("ana@example.test","Hello","Body"); vm.saveDraft(); runCurrent()
        assertTrue(vm.composer.value.uncertain); vm.saveDraft(); runCurrent()
        val restored=vm(saved); restored.saveDraft(); runCurrent()
        assertEquals(1,inbox.drafts.size); assertEquals("Body",restored.composer.value.body)
    }
    @Test fun `trash requests open review rather than removing mail locally`() = runTest(dispatcher) {
        inbox.pages += Outcome.Ok(InboxPage(listOf(message("one")),null))
        val vm=vm(); runCurrent(); vm.change(message("one"),MailChange.Trash); runCurrent()
        assertEquals(MailChange.Trash,inbox.changes.single().second)
        vm.effect.test {assertEquals(HubEffect.Review("action"),awaitItem())}
    }
    @Test fun `in flight submission survives process death and cannot discard or replace the composer`() = runTest(dispatcher) {
        val saved=SavedStateHandle(); val gate=CompletableDeferred<Unit>(); inbox.draftGate=gate
        val vm=vm(saved); runCurrent(); vm.compose("ana@example.test","Hello","Original body"); vm.saveDraft(); runCurrent()
        vm.closeComposer(); vm.compose(body="Replacement"); vm.editComposer(body="Changed")
        assertEquals("Original body",vm.composer.value.body)
        val restored=vm(saved); restored.saveDraft(); runCurrent()
        assertTrue(restored.composer.value.uncertain); assertEquals(1,inbox.drafts.size)
        gate.complete(Unit); runCurrent()
    }
    @Test fun `AI generation blocks duplicate submits and does not create a draft`() = runTest(dispatcher) {
        val vm=vm(); runCurrent(); vm.writing(task=WritingTask.Translate,text="Hello",language="Spanish")
        vm.generate(); vm.generate(); runCurrent()
        assertEquals("Hola",vm.writer.value.result); assertEquals(1,inbox.writes.size); assertTrue(inbox.drafts.isEmpty())
    }
    @Test fun `reader retries a failed message without navigating away`() = runTest(dispatcher) {
        inbox.readResult=Outcome.Err(AppError.Network(false))
        val vm=vm(); runCurrent(); vm.open(message("one")); runCurrent()
        assertTrue(vm.state.value.error is AppError.Network)
        inbox.readResult=Outcome.Ok(OpenMessage(message("one"),"me@example.test","Loaded body",false))
        vm.retryMessage(); runCurrent()
        assertEquals("Loaded body",vm.state.value.selected?.body); assertNull(vm.state.value.error)
    }
    @Test fun `server refusing draft storage leaves the composer editable`() = runTest(dispatcher) {
        inbox.draftResult=Outcome.Err(AppError.Unavailable(ErrorReason.MailNotConfigured,"Storage is not initialized"))
        val vm=vm(); runCurrent(); vm.compose("ana@example.test","Hello","Body"); vm.saveDraft(); runCurrent()
        assertFalse(vm.composer.value.uncertain); vm.editComposer(body="Edited body")
        assertEquals("Edited body",vm.composer.value.body)
    }

    private fun message(id:String)=InboxMessage(id,"thread","Ana <ana@example.test>","Hello","","Preview",true,false)
    private class TestInbox : InboxRepository {
        val pages=ArrayDeque<Outcome<InboxPage>>()
        var firstPage: CompletableDeferred<Outcome<InboxPage>>?=null
        val lists=mutableListOf<Triple<MailFolder,String,String?>>()
        val drafts=mutableListOf<ComposeDraft>(); val writes=mutableListOf<WritingRequest>()
        val changes=mutableListOf<Triple<String,MailChange,String>>()
        var draftGate: CompletableDeferred<Unit>?=null
        var readResult: Outcome<OpenMessage> = Outcome.Err(AppError.NotFound("Missing"))
        var draftResult: Outcome<DraftReceipt> = Outcome.Ok(DraftReceipt("draft","action","ana@example.test","Hello"))
        override suspend fun list(folder:MailFolder,query:String,cursor:String?):Outcome<InboxPage> {
            lists += Triple(folder,query,cursor)
            val gate=firstPage; firstPage=null
            return gate?.await() ?: pages.removeFirstOrNull() ?: Outcome.Ok(InboxPage(emptyList(),null))
        }
        override suspend fun read(id:String):Outcome<OpenMessage> = readResult
        override suspend fun change(id:String,change:MailChange,requestId:String):Outcome<MailChangeReceipt> {changes += Triple(id,change,requestId); return Outcome.Ok(MailChangeReceipt(if(change==MailChange.Trash) "action" else null,change==MailChange.Trash))}
        override suspend fun saveDraft(draft:ComposeDraft):Outcome<DraftReceipt> {drafts+=draft; draftGate?.await(); return draftResult}
        override suspend fun write(request:WritingRequest):Outcome<String> {writes+=request; return Outcome.Ok("Hola")}
    }
}
