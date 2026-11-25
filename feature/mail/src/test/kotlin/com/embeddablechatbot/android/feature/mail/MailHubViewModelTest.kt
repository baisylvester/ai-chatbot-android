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

    @Test fun `labels load with the mailbox and browsing a label narrows the list`() = runTest(dispatcher) {
        val vm=vm(); runCurrent()
        assertEquals(listOf("Clients","Travel"),vm.state.value.labels.map{it.name})
        vm.selectLabel(MailLabel("Label_1","Clients")); runCurrent()
        assertEquals("Label_1",inbox.labelLists.last()); assertEquals(MailFolder.All,vm.state.value.folder)
        vm.selectFolder(MailFolder.Inbox); runCurrent()
        assertNull(inbox.labelLists.last()); assertNull(vm.state.value.labelId)
    }
    @Test fun `a failed label load keeps the mailbox usable`() = runTest(dispatcher) {
        inbox.labelsResult=Outcome.Err(AppError.Network(false))
        inbox.pages += Outcome.Ok(InboxPage(listOf(message("one")),null))
        val vm=vm(); runCurrent()
        assertTrue(vm.state.value.loaded); assertNull(vm.state.value.error); assertTrue(vm.state.value.labels.isEmpty())
    }
    @Test fun `editing labels sends only the difference`() = runTest(dispatcher) {
        val msg=message("one").copy(labelIds=listOf("Label_1"))
        inbox.pages += Outcome.Ok(InboxPage(listOf(msg),null))
        val vm=vm(); runCurrent()
        vm.setLabels(msg,setOf("Label_1")); runCurrent()
        assertTrue(inbox.relabels.isEmpty())
        vm.setLabels(msg,setOf("Label_2")); runCurrent()
        assertEquals(Triple(listOf("one"),listOf("Label_2"),listOf("Label_1")),inbox.relabels.single())
        assertEquals(listOf("Label_2"),vm.state.value.messages.single().labelIds)
    }
    @Test fun `attachments are limited to ten megabytes and ten files`() = runTest(dispatcher) {
        val vm=vm(); runCurrent(); vm.compose("ana@example.test","Files","See attached")
        vm.addAttachment("big.bin","application/octet-stream",ByteArray(6*1024*1024))
        vm.addAttachment("big2.bin","application/octet-stream",ByteArray(5*1024*1024))
        assertEquals(1,vm.composer.value.attachments.size); assertTrue(vm.composer.value.error is AppError.Invalid)
        vm.addAttachment("empty.txt","text/plain",ByteArray(0)); assertEquals(1,vm.composer.value.attachments.size)
        vm.addAttachment("big.bin","application/octet-stream",ByteArray(6*1024*1024)); assertEquals(1,vm.composer.value.attachments.size) // same file twice
        vm.removeAttachment(vm.composer.value.attachments.single().id); assertTrue(vm.composer.value.attachments.isEmpty())
        repeat(11){ vm.addAttachment("f$it.txt","text/plain",byteArrayOf(1)) }
        assertEquals(OutgoingAttachment.MAX_FILES,vm.composer.value.attachments.size)
    }
    @Test fun `formatted drafts carry their files and format to the server`() = runTest(dispatcher) {
        val vm=vm(); runCurrent(); vm.compose("ana@example.test","Report","Hello team")
        val selection=vm.formatBody(0,5,TextStyleMark.Bold)
        assertEquals("**Hello** team",vm.composer.value.body); assertEquals(2 to 7,selection); assertTrue(vm.composer.value.formatted)
        vm.addAttachment("report.pdf","application/pdf",byteArrayOf(1,2,3)); vm.saveDraft(); runCurrent()
        val draft=inbox.drafts.single()
        assertTrue(draft.formatted); assertEquals("report.pdf",draft.attachments.single().filename)
    }
    @Test fun `summarizing an attachment opens the summary tool with its text`() = runTest(dispatcher) {
        inbox.readResult=Outcome.Ok(OpenMessage(message("one"),"me@example.test","Body",false,listOf(MessageAttachment("1","terms.pdf","application/pdf",10))))
        val vm=vm(); runCurrent(); vm.open(message("one")); runCurrent()
        vm.summarizeAttachment(vm.state.value.selected!!.attachments.single()); runCurrent()
        assertEquals(HubTab.Writing,vm.state.value.tab); assertEquals(WritingTask.Summary,inbox.writes.single().operation)
        assertEquals("Contract terms",inbox.writes.single().text)
    }
    @Test fun `opening an attachment emits the file with its real name`() = runTest(dispatcher) {
        inbox.readResult=Outcome.Ok(OpenMessage(message("one"),"me@example.test","Body",false,listOf(MessageAttachment("1","terms.pdf","application/pdf",10))))
        val vm=vm(); runCurrent(); vm.open(message("one")); runCurrent()
        vm.openAttachment(vm.state.value.selected!!.attachments.single()); runCurrent()
        vm.effect.test { val effect=awaitItem() as HubEffect.OpenFile; assertEquals("terms.pdf",effect.file.filename) }
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
        val labelLists=mutableListOf<String?>()
        val relabels=mutableListOf<Triple<List<String>,List<String>,List<String>>>()
        var labelsResult: Outcome<List<MailLabel>> = Outcome.Ok(listOf(MailLabel("Label_1","Clients"),MailLabel("Label_2","Travel")))
        var textResult: Outcome<AttachmentText> = Outcome.Ok(AttachmentText("terms.pdf","Contract terms",false))
        override suspend fun labels()=labelsResult
        override suspend fun createLabel(name:String):Outcome<MailLabel> = Outcome.Ok(MailLabel("Label_9",name))
        override suspend fun relabel(ids:List<String>,add:List<String>,remove:List<String>):Outcome<Unit> {relabels+=Triple(ids,add,remove); return Outcome.Ok(Unit)}
        override suspend fun attachment(messageId:String,partId:String):Outcome<DownloadedFile> = Outcome.Ok(DownloadedFile(partId,"application/pdf",byteArrayOf(1,2)))
        override suspend fun attachmentText(messageId:String,partId:String)=textResult
        override suspend fun list(folder:MailFolder,query:String,cursor:String?,labelId:String?):Outcome<InboxPage> {
            labelLists += labelId
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
