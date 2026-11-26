package com.embeddablechatbot.android.core.domain.mail

import com.embeddablechatbot.android.core.domain.model.Outcome

enum class MailFolder(val wire: String, val title: String) {
    Inbox("inbox", "Inbox"), Unread("unread", "Unread"), Starred("starred", "Starred"),
    Attachments("attachments", "Attachments"), Sent("sent", "Sent"), Drafts("drafts", "Drafts"),
    Archive("archive", "Archive"), Spam("spam", "Spam"), Trash("trash", "Trash"), All("all", "All mail"),
}
/** [labelIds] holds only the user's own Gmail labels, never system labels. */
data class InboxMessage(
    val id: String, val threadId: String, val from: String, val subject: String,
    val date: String, val snippet: String, val unread: Boolean, val starred: Boolean,
    val labelIds: List<String> = emptyList(),
)
/** [savedAt] is set when the page is the offline copy saved on the phone (epoch millis). */
data class InboxPage(val messages: List<InboxMessage>, val nextCursor: String?, val savedAt: Long? = null)
/** A file attached to a received message; [partId] addresses it within that message. */
data class MessageAttachment(val partId: String, val filename: String, val mimeType: String, val size: Long) {
    /** Formats the server can read for summaries: documents as text, images through the AI model. */
    val readable: Boolean get() = filename.substringAfterLast('.', "").lowercase() in setOf("pdf", "docx", "doc", "xlsx", "pptx", "txt", "text", "md", "markdown", "csv", "tsv", "html", "htm", "png", "jpg", "jpeg", "webp", "heic", "heif") ||
        mimeType in setOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/tab-separated-values", "text/plain", "text/markdown", "text/csv", "text/html", "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif")
}
data class OpenMessage(val summary: InboxMessage, val to: String, val body: String, val truncated: Boolean, val attachments: List<MessageAttachment> = emptyList(), val offline: Boolean = false)
data class MailLabel(val id: String, val name: String, val color: String? = null)
/** Someone the user has exchanged mail with, suggested while typing a recipient. */
data class MailContact(val name: String, val email: String)

/** Background colors Gmail accepts for labels (the server refuses any other). */
val LabelColors = listOf("#fb4c2f", "#ffad47", "#fad165", "#16a766", "#43d692", "#4a86e8", "#2da2bb", "#a479e2", "#f691b3", "#666666")
data class DownloadedFile(val filename: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is DownloadedFile && filename == other.filename && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * filename.hashCode() + bytes.contentHashCode()
}
data class AttachmentText(val filename: String, val text: String, val truncated: Boolean)
/** A file the user picked for a new draft. Held in memory only; the bytes are sent once, with the draft. */
class OutgoingAttachment(val id: String, val filename: String, val mimeType: String, val bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()
    companion object {
        const val MAX_TOTAL_BYTES = 10L * 1024 * 1024
        const val MAX_FILES = 10
    }
}
enum class MailChange(val wire: String) {
    Archive("archive"), Inbox("move_to_inbox"), Read("mark_read"), Unread("mark_unread"),
    Star("star"), Unstar("unstar"), Spam("mark_spam"), Trash("trash"),
}
data class MailChangeReceipt(val actionId: String?, val waitingForApproval: Boolean)
/** [formatted] sends the body's limited Markdown (bold, italic, lists, links) as HTML with a plain-text alternative. */
data class ComposeDraft(
    val requestId: String, val to: String, val subject: String, val body: String, val replyToMessageId: String? = null,
    val formatted: Boolean = false, val attachments: List<OutgoingAttachment> = emptyList(),
)
data class DraftReceipt(val draftId: String, val actionId: String, val to: String, val subject: String)
enum class WritingTask(val wire: String, val title: String) { Write("write", "Write"), Reply("reply", "Reply"), Summary("summary", "Summary"), Grammar("grammar", "Grammar"), Translate("translate", "Translate") }
data class WritingRequest(val operation: WritingTask, val text: String, val tone: String = "Professional", val length: String = "Medium", val language: String = "English")

/** Native mail operations. Provider credentials remain exclusively on the server. */
interface InboxRepository {
    /** [labelId] narrows the folder to one of the user's labels. */
    suspend fun list(folder: MailFolder, query: String, cursor: String? = null, labelId: String? = null): Outcome<InboxPage>
    suspend fun labels(): Outcome<List<MailLabel>>
    suspend fun createLabel(name: String): Outcome<MailLabel>
    /** Renames and/or recolors one of the user's labels; [color] must be one of [LabelColors]. */
    suspend fun updateLabel(id: String, name: String?, color: String?): Outcome<MailLabel>
    /** Deletes the label only; messages that carried it are kept. */
    suspend fun deleteLabel(id: String): Outcome<Unit>
    /** Adds and removes the user's own labels. Reversible, so it applies immediately. */
    suspend fun relabel(ids: List<String>, add: List<String>, remove: List<String>): Outcome<Unit>
    suspend fun attachment(messageId: String, partId: String): Outcome<DownloadedFile>
    suspend fun attachmentText(messageId: String, partId: String): Outcome<AttachmentText>
    suspend fun read(id: String): Outcome<OpenMessage>
    suspend fun change(id: String, change: MailChange, requestId: String): Outcome<MailChangeReceipt>
    suspend fun saveDraft(draft: ComposeDraft): Outcome<DraftReceipt>
    suspend fun write(request: WritingRequest): Outcome<String>
    /** Pinned messages (kept by the app, synced across the user's devices), newest pin first. */
    suspend fun pinned(): Outcome<List<InboxMessage>> = Outcome.Ok(emptyList())
    suspend fun pin(id: String): Outcome<Unit> = Outcome.Ok(Unit)
    suspend fun unpin(id: String): Outcome<Unit> = Outcome.Ok(Unit)
    /** Deletes the offline copy (another mailbox was selected). */
    suspend fun clearOfflineCopy() {}
    /** People from the user's recent mail, most frequent first; the app filters them while typing. */
    suspend fun contacts(): Outcome<List<MailContact>> = Outcome.Ok(emptyList())
}

data class MailPrompt(val id: String, val title: String, val description: String, val category: String, val text: String)
object MailPrompts {
    val all = listOf(
        MailPrompt("meeting", "Arrange a meeting", "An invitation with a clear purpose and next steps.", "Work", "Write a professional meeting invitation. Include the purpose, proposed time and an agenda. Ask for confirmation."),
        MailPrompt("application", "Job application", "Introduce your experience with confidence.", "Work", "Write a job application email. Highlight my relevant experience, motivation and availability."),
        MailPrompt("followup", "A thoughtful follow-up", "Keep the conversation moving.", "Work", "Write a polite follow-up after a business meeting. Summarize next steps and ask if any information is needed."),
        MailPrompt("update", "Project update", "Share progress, blockers and next steps.", "Work", "Write a concise project update covering progress, current blockers and next steps."),
        MailPrompt("outreach", "Customer engagement", "Start a useful conversation with your audience.", "Marketing", "Write a friendly customer outreach email. Explain the benefit clearly and end with one helpful call to action."),
        MailPrompt("launch", "Product announcement", "Introduce something worth opening.", "Marketing", "Write an engaging product announcement explaining what is new, who it helps and how to learn more."),
        MailPrompt("newsletter", "Company newsletter", "Turn your latest news into a clear update.", "Marketing", "Write a company newsletter with a short introduction, three highlights and a useful closing."),
        MailPrompt("proposal", "A persuasive proposal", "Explain the value, scope and next step.", "Sales", "Write a sales proposal email explaining the customer's need, our solution, its value and a suggested next step."),
        MailPrompt("sales", "Start a sales conversation", "Make a relevant, personal introduction.", "Sales", "Write a short sales introduction focused on the recipient's needs. Avoid exaggerated claims and ask one specific question."),
        MailPrompt("thanks", "Say thank you", "Make your appreciation feel personal.", "Personal", "Write a warm thank-you email that explains what I appreciate and why it matters."),
        MailPrompt("apology", "A sincere apology", "Acknowledge the problem and offer a next step.", "Personal", "Write a sincere apology email. Take responsibility, acknowledge the impact and explain the next step."),
        MailPrompt("leave", "Time-off request", "Make the dates and handover easy to understand.", "Work", "Write a time-off request with the dates, a handover plan and a request for approval."),
    )
}
