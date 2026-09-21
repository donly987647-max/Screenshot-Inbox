import app.captureinbox.*
import java.time.LocalDate

fun main() {
    val today = LocalDate.of(2026, 9, 21)
    var count = 0
    fun verify(condition: Boolean, label: String) { check(condition) { label }; count++ }
    fun p(s: String) = CaptureParser.parse(s, today)
    verify(p("커피 쿠폰\n유효기간 2026.09.30").date == "2026-09-30", "Korean expiry")
    verify(p("커피 쿠폰\n유효기간 2026.09.30").category == Category.COUPON, "coupon")
    verify(p("공연 티켓\n일시 2026년 10월 3일").date == "2026-10-03", "Korean date")
    verify(p("상품 구매\n판매가 139,000원").price == "139,000원", "price")
    verify(p("상품 구매\n판매가 139,000원").date == null, "no date fabrication")
    verify(p("COFFEE COUPON\nExpires: Sep 30, 2026").date == "2026-09-30", "English date")
    verify(p("쿠폰\n유효기간 2026.09.01 ~ 09.30").date == "2026-09-30", "range end")
    verify(p("행사\n일시 2026.09.25 ~ 2026.09.27").date == "2026-09-25", "event start")
    verify(p("쿠폰\n유효기간 2025.09.30").date == "2025-09-30", "past year remains past")
    verify(p("쿠폰\n유효기간 9월 30일").warning!!.contains("연도"), "yearless warning")
    verify(p("메모\n금액 $9.30").date == null, "decimal price not date")
    verify(p("쿠폰\n유효기간 2026.02.30").date == null, "invalid date")
    verify(CaptureParser.parseDateStrict("2024-02-29") != null, "leap year")
    verify(CaptureParser.parseDateStrict("2025-02-29") == null, "invalid leap year")
    verify(CaptureParser.parseDateStrict("2026-9-1") == null, "strict formatting")
    verify(CaptureParser.safeUrl("javascript:alert(1)") == null, "unsafe scheme")
    verify(CaptureParser.safeUrl("https://user:pass@example.com") == null, "URL credentials")
    verify(CaptureParser.safeUrl("https://example.com/path?q=hello") != null, "safe URL")
    verify(p("저장한 페이지\nhttps://example.com/a").url == "https://example.com/a", "link extraction")
    verify(p("카페 모퉁이\n주소: 서울 성동구 연무장길 10").category == Category.PLACE, "place")
    verify(p("카페 모퉁이\n주소: 서울 성동구 연무장길 10").location!!.contains("성동구"), "address")
    verify(p("9:41\n5G\n커피 쿠폰\n유효기간 2026.09.30").title == "커피 쿠폰", "status bar ignored")
    verify(p("").title == "새 스크린샷", "empty OCR preserved")
    verify(p("메모만 있습니다").date == null, "no invented date")
    verify(Capture(title="쿠폰",text="",date="2026-09-30").dueLabel(today) == "날짜 확인 필요", "confirmation gate")
    verify(!Capture(title="완료",text="",date="2026-09-30",done=true).isUpcoming(today), "completed hidden")
    verify(!Capture(title="지남",text="",date="2026-09-20").isUpcoming(today), "expired hidden")
    verify(Capture(title="오늘",text="",date="2026-09-21",dateConfirmed=true).dueLabel(today) == "오늘", "today")
    verify(CaptureParser.parse("쿠폰 유효기간 1월 5일", LocalDate.of(2026,12,31)).date == "2026-01-05", "no silent year rollover")
    println("$count parser and state checks passed")
}
