package app.captureinbox

import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

enum class Category(val label: String) {
    COUPON("쿠폰"), EVENT("일정"), PRODUCT("상품"), PLACE("장소"), NOTE("메모")
}

data class Capture(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val text: String,
    val category: Category = Category.NOTE,
    val createdAt: Long = System.currentTimeMillis(),
    val imageName: String? = null,
    val date: String? = null,
    val dateConfirmed: Boolean = false,
    val remindDays: Int? = null,
    val done: Boolean = false,
    val price: String? = null,
    val url: String? = null,
    val location: String? = null,
    val digest: String? = null,
    val sample: Boolean = false,
    val warning: String? = null
)

data class Extraction(
    val title: String, val text: String, val category: Category,
    val date: String?, val price: String?, val url: String?,
    val location: String?, val warning: String?
)

object CaptureParser {
    private val iso = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val expiry = Regex("유효|만료|사용\\s*기한|사용\\s*기간|expires?|expiry|valid\\s*(until|thru|through)", RegexOption.IGNORE_CASE)
    private val dateCue = Regex("일시|일정|날짜|기간|까지|마감|예약|공연|행사|date|when|event|concert", RegexOption.IGNORE_CASE)
    private val fullDate = Regex("(?<![\\d])((?:19|20)\\d{2})\\s*[년./-]\\s*(\\d{1,2})\\s*[월./-]\\s*(\\d{1,2})(?:\\s*일)?(?!\\d)")
    private val shortDate = Regex("(?<![\\d.$₩/\\-])(\\d{1,2})\\s*([월./])\\s*(\\d{1,2})(?:\\s*일)?(?![\\d원.%])")
    private val englishDate = Regex("\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{1,2})(?:st|nd|rd|th)?[,]?\\s+((?:19|20)\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private data class Candidate(val date: LocalDate, val index: Int, val inferredYear: Boolean, val priority: Boolean)

    fun parseDateStrict(input: String): LocalDate? =
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(input)) null
        else runCatching { LocalDate.parse(input, iso) }.getOrNull()

    fun safeUrl(raw: String?): String? {
        if (raw == null || raw.length > 2048 || raw.any { it.isISOControl() || it.isWhitespace() }) return null
        return runCatching {
            URI(raw).let { uri ->
                raw.takeIf { uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }
            }
        }.getOrNull()
    }

    private fun makeDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun parse(raw: String, today: LocalDate = LocalDate.now()): Extraction {
        val text = raw.take(100_000).replace(Regex("[\\p{Cf}\\p{Cc}&&[^\\n\\t]]"), "").trim()
        val category = when {
            Regex("쿠폰|기프티|교환권|상품권|coupon|voucher|gift\\s*card", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Category.COUPON
            Regex("공연|콘서트|예약|세미나|전시|행사|일시|concert|reservation|event|ticket", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Category.EVENT
            Regex("장바구니|구매|배송비|판매가|할인가|정가|cart|buy\\s*now|price", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Category.PRODUCT
            Regex("주소|맛집|영업시간|식당|카페|restaurant|address|opening\\s*hours", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Category.PLACE
            else -> Category.NOTE
        }
        val price = Regex("(?:₩\\s*\\d[\\d,]*(?:\\.\\d{1,2})?|\\d[\\d,]*(?:\\.\\d{1,2})?\\s*원|\\$\\s*\\d[\\d,]*(?:\\.\\d{1,2})?)").find(text)?.value?.trim()
        val resolvedCategory = if (category == Category.NOTE && price != null) Category.PRODUCT else category
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        val title = lines.firstOrNull { line ->
            line.length >= 2 && !Regex("^[\\d: %.]+$").matches(line) &&
                line.lowercase() !in setOf("skt", "kt", "lg u+", "lte", "5g", "공유", "저장", "뒤로") &&
                !line.startsWith("http") && !fullDate.matches(line) && !expiry.containsMatchIn(line)
        }?.take(100) ?: if (text.isBlank()) "새 스크린샷" else "저장한 ${resolvedCategory.label}"
        val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text)
        val url = urls.mapNotNull { safeUrl(it.value.trimEnd('.', ',', ')', ']', ';', '!')) }.firstOrNull()
        val locationMatch = Regex("(?:주소|장소|위치|address|venue)\\s*[:：]?\\s*([^\\n]{3,120})", RegexOption.IGNORE_CASE).find(text)
        val location = locationMatch?.groupValues?.get(1)?.trim()
        fun priorityAt(index: Int): Boolean {
            val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val prev = text.lastIndexOf('\n', (start - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val context = text.substring(prev, end)
            return if (resolvedCategory == Category.COUPON) expiry.containsMatchIn(context) else dateCue.containsMatchIn(context)
        }
        val candidates = mutableListOf<Candidate>()
        val occupied = mutableListOf<IntRange>()
        fullDate.findAll(text).forEach { match ->
            occupied += match.range
            makeDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())?.let {
                candidates += Candidate(it, match.range.first, false, priorityAt(match.range.first))
            }
        }
        englishDate.findAll(text).forEach { match ->
            occupied += match.range
            makeDate(match.groupValues[3].toInt(), months.indexOf(match.groupValues[1].take(3).lowercase()) + 1, match.groupValues[2].toInt())?.let {
                candidates += Candidate(it, match.range.first, false, priorityAt(match.range.first))
            }
        }
        shortDate.findAll(text).forEach { match ->
            if (occupied.any { match.range.first in it || match.range.last in it }) return@forEach
            if (match.groupValues[2] != "월" && !expiry.containsMatchIn(text) && !dateCue.containsMatchIn(text)) return@forEach
            val previous = candidates.filter { it.index < match.range.first && !it.inferredYear }.maxByOrNull { it.index }
            val year = previous?.date?.year ?: today.year
            makeDate(year, match.groupValues[1].toInt(), match.groupValues[3].toInt())?.let {
                candidates += Candidate(it, match.range.first, true, priorityAt(match.range.first))
            }
        }
        val preferred = candidates.filter { it.priority }.ifEmpty { candidates }.sortedBy { it.index }
        val chosen = if (resolvedCategory == Category.COUPON) preferred.lastOrNull() else preferred.firstOrNull()
        val warnings = mutableListOf<String>()
        if (text.isBlank()) warnings += "글자를 찾지 못했어요. 원본을 보면서 내용을 입력해 주세요."
        if (chosen != null) warnings += "원본과 날짜를 확인해 주세요."
        if (chosen?.inferredYear == true) warnings += "연도가 생략되어 ${chosen.date.year}년으로 제안했어요."
        if (candidates.map { it.date }.distinct().size > 1) warnings += "날짜가 여러 개 발견되었어요."
        if (chosen != null && chosen.date.isBefore(today)) warnings += "이미 지난 날짜예요."
        return Extraction(title, text, resolvedCategory, chosen?.date?.toString(), price, url, location, warnings.joinToString(" ").ifEmpty { null })
    }
}

fun Capture.isUpcoming(today: LocalDate = LocalDate.now()): Boolean =
    !done && date?.let(CaptureParser::parseDateStrict)?.let { !it.isBefore(today) } == true

fun Capture.dueLabel(today: LocalDate = LocalDate.now()): String {
    if (done) return "사용 완료"
    val day = date?.let(CaptureParser::parseDateStrict) ?: return "날짜 없음"
    if (!dateConfirmed) return "날짜 확인 필요"
    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, day)
    return when { diff < 0 -> "${-diff}일 지남"; diff == 0L -> "오늘"; diff == 1L -> "내일"; else -> "D-$diff" }
}
