package com.embeddablechatbot.android.feature.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HubTab { Mail, Writing, Prompts }
data class MailHubState(
    val tab: HubTab = HubTab.Mail, val folder: MailFolder = MailFolder.Inbox, val query: String = "",
    val loading: Boolean = true, val loaded: Boolean = false, val loadingMore: Boolean = false,
    val messages: List<InboxMessage> = emptyList(), val cursor: String? = null, val status: MailStatus? = null,
    val error: AppError? = null, val selected: OpenMessage? = null, val selectedId: String? = null,
    val opening: Boolean = false, val changing: Boolean = false, val notice: String? = null,
    val undo: Pair<String, MailChange>? = null,
    /** The user's own Gmail labels, and the one being browsed (null for a folder). */
    val labels: List<MailLabel> = emptyList(), val labelId: String? = null,
    /** The attachment being downloaded or read, by part id. */
    val attachmentBusy: String? = null,
)
data class WriterState(val task: WritingTask = WritingTask.Write, val text: String = "", val tone: String = "Professional", val length: String = "Medium", val language: String = "English", val result: String = "", val busy: Boolean = false, val error: AppError? = null)
/** [attachments] live in memory only: after process death the user re-adds them. */
data class ComposerState(
    val visible: Boolean = false, val to: String = "", val subject: String = "", val body: String = "", val replyId: String? = null,
    val saving: Boolean = false, val uncertain: Boolean = false, val error: AppError? = null,
    val formatted: Boolean = false, val attachments: List<OutgoingAttachment> = emptyList(),
) {
    val attachmentBytes: Long get() = attachments.sumOf { it.size }
}
sealed interface HubEffect {
    data class Review(val id: String) : HubEffect
    /** A downloaded attachment for the screen to hand to another app. */
    data class OpenFile(val file: DownloadedFile) : HubEffect
}

@HiltViewModel
class MailHubViewModel @Inject constructor(
    private val inbox: InboxRepository, private val mail: MailRepository, private val config: ConfigRepository,
    private val preferences: PreferencesRepository, private val ids: IdGenerator, private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(MailHubState())
    val state = mutable.asStateFlow()
    private val writerMutable = MutableStateFlow(WriterState(text = saved["writer_text"] ?: ""))
    val writer = writerMutable.asStateFlow()
    private val composeMutable = MutableStateFlow(ComposerState(visible = saved["compose_visible"] ?: false, to = saved["compose_to"] ?: "", subject = saved["compose_subject"] ?: "", body = saved["compose_body"] ?: "", replyId = saved["compose_reply"], uncertain = saved["compose_uncertain"] ?: false, formatted = saved["compose_formatted"] ?: false))
    val composer = composeMutable.asStateFlow()
    val userPreferences = preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    private val effects = Channel<HubEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var generation = 0L
    private var writingGeneration = 0L
    init { refresh() }

    fun selectTab(tab: HubTab) { mutable.update { it.copy(tab = tab) } }
    fun selectFolder(folder: MailFolder) { mutable.update { it.copy(folder = folder, labelId = null, selected = null, selectedId = null, query = "", messages = emptyList(), loaded = false) }; refresh() }
    /** Browses every message (outside Spam and Trash) carrying one of the user's labels. */
    fun selectLabel(label: MailLabel) { mutable.update { it.copy(folder = MailFolder.All, labelId = label.id, selected = null, selectedId = null, query = "", messages = emptyList(), loaded = false) }; refresh() }
    private var labelsLoaded = false
    private fun loadLabels(force: Boolean = false) {
        if (labelsLoaded && !force) return
        labelsLoaded = true
        viewModelScope.launch {
            when (val result = inbox.labels()) {
                is Outcome.Ok -> mutable.update { it.copy(labels = result.value) }
                // Labels are an extra: the mailbox stays usable, and the next refresh tries again.
                is Outcome.Err -> labelsLoaded = false
            }
        }
    }
    fun createLabel(name: String) {
        val clean = name.trim().take(100)
        if (clean.isEmpty() || state.value.changing) return
        mutable.update { it.copy(changing = true, error = null) }
        viewModelScope.launch {
            when (val result = inbox.createLabel(clean)) {
                is Outcome.Ok -> { mutable.update { it.copy(changing = false, notice = "Label created", labels = (it.labels + result.value).sortedBy { l -> l.name.lowercase() }) } }
                is Outcome.Err -> mutable.update { it.copy(changing = false, error = result.error) }
            }
        }
    }
    /** Applies exactly the chosen set of the user's labels to one message. */
    fun setLabels(message: InboxMessage, chosen: Set<String>) {
        if (state.value.changing) return
        val current = message.labelIds.toSet()
        val add = (chosen - current).toList(); val remove = (current - chosen).toList()
        if (add.isEmpty() && remove.isEmpty()) return
        mutable.update { it.copy(changing = true, error = null) }
        viewModelScope.launch {
            when (val result = inbox.relabel(listOf(message.id), add, remove)) {
                is Outcome.Ok -> {
                    val updated = message.copy(labelIds = chosen.toList())
                    mutable.update { s -> s.copy(changing = false, notice = "Labels updated",
                        messages = s.messages.map { if (it.id == message.id) updated else it },
                        selected = s.selected?.let { if (it.summary.id == message.id) it.copy(summary = updated) else it }) }
                    if (state.value.labelId != null && state.value.labelId !in chosen) refresh()
                }
                is Outcome.Err -> mutable.update { it.copy(changing = false, error = result.error) }
            }
        }
    }
    fun openAttachment(attachment: MessageAttachment) {
        val messageId = state.value.selectedId ?: return
        if (state.value.attachmentBusy != null) return
        mutable.update { it.copy(attachmentBusy = attachment.partId, error = null) }
        viewModelScope.launch {
            val result = inbox.attachment(messageId, attachment.partId)
            mutable.update { it.copy(attachmentBusy = null, error = (result as? Outcome.Err)?.error) }
            if (result is Outcome.Ok) effects.send(HubEffect.OpenFile(result.value.copy(filename = attachment.filename)))
        }
    }
    /** Reads a document attachment's text on the server and opens it in the Summary writing tool. */
    fun summarizeAttachment(attachment: MessageAttachment) {
        val messageId = state.value.selectedId ?: return
        if (state.value.attachmentBusy != null || writer.value.busy) return
        mutable.update { it.copy(attachmentBusy = attachment.partId, error = null) }
        viewModelScope.launch {
            when (val result = inbox.attachmentText(messageId, attachment.partId)) {
                is Outcome.Ok -> {
                    mutable.update { it.copy(attachmentBusy = null) }
                    writing(task = WritingTask.Summary, text = result.value.text)
                    closeMessage(); selectTab(HubTab.Writing); generate()
                }
                is Outcome.Err -> mutable.update { it.copy(attachmentBusy = null, error = result.error) }
            }
        }
    }
    fun query(value: String) { mutable.update { it.copy(query = value.take(300)) } }
    fun search() = refresh()
    fun refresh() {
        val token = ++generation
        val folder = state.value.folder; val query = state.value.query.trim(); val labelId = state.value.labelId
        mutable.update { it.copy(loading = true, loadingMore = false, error = null) }
        viewModelScope.launch {
            val status = mail.status(config.config.value.chatbotId)
            if (token != generation) return@launch
            if (status is Outcome.Err) { mutable.update { it.copy(loading = false, error = status.error) }; return@launch }
            val current = (status as Outcome.Ok).value
            mutable.update { it.copy(status = current) }
            if (!current.eligible || current.connection?.status != ConnectionStatus.Active) {
                mutable.update { it.copy(loading = false, loaded = false, messages = emptyList(), cursor = null) }; return@launch
            }
            loadLabels()
            when (val result = inbox.list(folder, query, null, labelId)) {
                is Outcome.Ok -> if (token == generation) mutable.update { it.copy(loading = false, loaded = true, messages = result.value.messages.distinctBy(InboxMessage::id), cursor = result.value.nextCursor) }
                is Outcome.Err -> if (token == generation) mutable.update { it.copy(loading = false, error = result.error) }
            }
        }
    }
    fun more() {
        val s = state.value; val cursor = s.cursor ?: return
        if (s.loading || s.loadingMore) return
        val token = generation
        mutable.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = inbox.list(s.folder, s.query.trim(), cursor, s.labelId)) {
                is Outcome.Ok -> if (token == generation) mutable.update { it.copy(loadingMore = false, messages = (it.messages + result.value.messages).distinctBy(InboxMessage::id), cursor = result.value.nextCursor) }
                is Outcome.Err -> if (token == generation) mutable.update { it.copy(loadingMore = false, error = result.error) }
            }
        }
    }
    fun open(message: InboxMessage) = loadMessage(message.id)
    fun retryMessage() { state.value.selectedId?.let(::loadMessage) }
    private fun loadMessage(id: String) {
        mutable.update { it.copy(opening = true, selectedId = id, selected = null, error = null) }
        viewModelScope.launch {
            when (val result = inbox.read(id)) {
                is Outcome.Ok -> if (state.value.selectedId == id) mutable.update { it.copy(selected = result.value, opening = false) }
                is Outcome.Err -> if (state.value.selectedId == id) mutable.update { it.copy(opening = false, error = result.error) }
            }
        }
    }
    fun closeMessage() { mutable.update { it.copy(selected = null, selectedId = null, opening = false, error = null) } }
    fun change(message: InboxMessage, action: MailChange) {
        if (state.value.changing) return
        mutable.update { it.copy(changing = true, error = null) }
        val key = "action_${action.wire}_${message.id}"
        val requestId: String = saved[key] ?: ids.newId().also { saved[key] = it }
        viewModelScope.launch {
            when (val result = inbox.change(message.id, action, requestId)) {
                is Outcome.Ok -> {
                    saved.remove<String>(key)
                    val inverse = when (action) { MailChange.Archive, MailChange.Spam -> MailChange.Inbox; MailChange.Star -> MailChange.Unstar; MailChange.Unstar -> MailChange.Star; MailChange.Read -> MailChange.Unread; MailChange.Unread -> MailChange.Read; else -> null }
                    mutable.update { it.copy(changing = false, notice = if (result.value.waitingForApproval) "Waiting for your approval" else "Mailbox updated", undo = inverse?.let { message.id to it }, selected = null, selectedId = null) }
                    result.value.actionId?.let { effects.send(HubEffect.Review(it)) }
                    refresh()
                }
                is Outcome.Err -> mutable.update { it.copy(changing = false, error = result.error) }
            }
        }
    }
    fun undo() {
        val change = state.value.undo ?: return
        if (state.value.changing) return
        mutable.update { it.copy(changing = true, undo = null) }
        viewModelScope.launch {
            when (val r = inbox.change(change.first, change.second, ids.newId())) {
                is Outcome.Ok -> { mutable.update { it.copy(changing = false, notice = "Change undone") }; refresh() }
                is Outcome.Err -> mutable.update { it.copy(changing = false, error = r.error) }
            }
        }
    }
    fun compose(to: String = "", subject: String = "", body: String = "", replyId: String? = null) {
        if (composer.value.saving) return
        saved["draft_request"] = ids.newId(); saved["compose_uncertain"] = false
        composeMutable.value = ComposerState(true, to, subject, body, replyId)
        persistComposer()
    }
    fun editComposer(to: String? = null, subject: String? = null, body: String? = null) {
        if (composer.value.saving || composer.value.uncertain) return
        composeMutable.update { it.copy(to = to?.take(500) ?: it.to, subject = subject?.take(300) ?: it.subject, body = body?.take(20000) ?: it.body, error = null) }; persistComposer()
    }
    private fun persistComposer() { val c = composer.value; saved["compose_visible"] = c.visible; saved["compose_to"] = c.to; saved["compose_subject"] = c.subject; saved["compose_body"] = c.body; saved["compose_reply"] = c.replyId; saved["compose_uncertain"] = c.uncertain; saved["compose_formatted"] = c.formatted }
    fun setFormatted(on: Boolean) {
        if (composer.value.saving || composer.value.uncertain) return
        composeMutable.update { it.copy(formatted = on) }; persistComposer()
    }
    /** Adds a picked file, refusing more than [OutgoingAttachment.MAX_FILES] files or 10 MB in total. */
    fun addAttachment(filename: String, mimeType: String, bytes: ByteArray) {
        val c = composer.value
        if (c.saving || c.uncertain) return
        // Two picker results for one file (a quick double tap opens two pickers) attach it once.
        if (c.attachments.any { it.filename == filename && it.bytes.contentEquals(bytes) }) return
        val error = when {
            bytes.isEmpty() -> "This file is empty."
            c.attachments.size >= OutgoingAttachment.MAX_FILES -> "Attach at most ${OutgoingAttachment.MAX_FILES} files."
            c.attachmentBytes + bytes.size > OutgoingAttachment.MAX_TOTAL_BYTES -> "Attachments can total at most 10 MB."
            else -> null
        }
        if (error != null) { composeMutable.update { it.copy(error = AppError.Invalid(error)) }; return }
        composeMutable.update { it.copy(attachments = it.attachments + OutgoingAttachment(ids.newId(), filename.ifBlank { "attachment" }, mimeType.ifBlank { "application/octet-stream" }, bytes), error = null) }
    }
    fun removeAttachment(id: String) {
        if (composer.value.saving || composer.value.uncertain) return
        composeMutable.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }
    /** Wraps the selection in Markdown markers for the formatted email. */
    fun formatBody(start: Int, end: Int, style: TextStyleMark): Pair<Int, Int>? {
        val c = composer.value
        if (c.saving || c.uncertain) return null
        val s = start.coerceIn(0, c.body.length); val e = end.coerceIn(s, c.body.length)
        val (body, selection) = applyMark(c.body, s, e, style)
        if (body.length > 20000) return null
        composeMutable.update { it.copy(body = body, formatted = true, error = null) }; persistComposer()
        return selection
    }
    fun closeComposer() { if (!composer.value.saving) resetComposer() }
    private fun resetComposer() { composeMutable.value = ComposerState(); persistComposer() }
    fun saveDraft() {
        val c = composer.value
        if (c.saving || c.uncertain) return
        if (c.body.isBlank() || (c.replyId == null && (c.to.isBlank() || c.subject.isBlank()))) { composeMutable.update { it.copy(error = AppError.Invalid("Add a recipient, subject and message.")) }; return }
        val requestId: String = saved["draft_request"] ?: ids.newId().also { saved["draft_request"] = it }
        composeMutable.update { it.copy(saving = true, error = null) }
        // A process death after submission must restore an uncertain draft, never an editable retry.
        saved["compose_uncertain"] = true
        viewModelScope.launch {
            when (val result = inbox.saveDraft(ComposeDraft(requestId,c.to.trim(),c.subject.trim(),c.body,c.replyId,c.formatted,c.attachments))) {
                is Outcome.Ok -> { resetComposer(); effects.send(HubEffect.Review(result.value.actionId)); refresh() }
                is Outcome.Err -> {
                    val error = result.error
                    val storageUnavailable = error is AppError.Unavailable && error.reason == ErrorReason.MailNotConfigured
                    val uncertain = !storageUnavailable && result.error !is AppError.Invalid && result.error !is AppError.Unauthorized && result.error !is AppError.NotConfigured
                    composeMutable.update { it.copy(saving = false, uncertain = uncertain, error = result.error) }; persistComposer()
                }
            }
        }
    }
    fun writing(task: WritingTask? = null, text: String? = null, tone: String? = null, length: String? = null, language: String? = null) {
        if (writer.value.busy) return
        writerMutable.update { it.copy(task = task ?: it.task, text = text?.take(20000) ?: it.text, tone = tone ?: it.tone, length = length ?: it.length, language = language ?: it.language, result = "", error = null) }
        saved["writer_text"] = writer.value.text
    }
    fun generate() {
        val w = writer.value
        if (w.busy || w.text.isBlank()) return
        val token = ++writingGeneration
        writerMutable.update { it.copy(busy = true, result = "", error = null) }
        viewModelScope.launch {
            val result = inbox.write(WritingRequest(w.task,w.text,w.tone,w.length,w.language))
            if (token != writingGeneration) return@launch
            writerMutable.update { when (result) { is Outcome.Ok -> it.copy(busy = false, result = result.value); is Outcome.Err -> it.copy(busy = false, error = result.error) } }
        }
    }
    fun usePrompt(prompt: MailPrompt) { writing(task = WritingTask.Write, text = prompt.text); selectTab(HubTab.Writing) }
    fun assistMessage(task: WritingTask) { val m = state.value.selected ?: return; writing(task = task, text = m.body); closeMessage(); selectTab(HubTab.Writing) }
    fun toggleFavorite(id: String) { viewModelScope.launch { preferences.update { p -> p.copy(favoritePrompts = if (id in p.favoritePrompts) p.favoritePrompts - id else p.favoritePrompts + id) } } }
}
