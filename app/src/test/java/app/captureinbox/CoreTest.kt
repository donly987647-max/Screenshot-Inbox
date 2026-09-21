package app.captureinbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoreTest {
    private val today = LocalDate.of(2026, 9, 21)
    private fun p(text: String) = CaptureParser.parse(text, today)
    private fun verify(condition: Boolean, label: String) = assertTrue(label, condition)
    @Test fun case01() { verify(p("커피 쿠폰\n유효기간 2026.09.30").date == "2026-09-30", "Korean expiry") }
    @Test fun case02() { verify(p("커피 쿠폰\n유효기간 2026.09.30").category == Category.COUPON, "coupon") }
    @Test fun case03() { verify(p("공연 티켓\n일시 2026년 10월 3일").date == "2026-10-03", "Korean date") }
    @Test fun case04() { verify(p("상품 구매\n판매가 139,000원").price == "139,000원", "price") }
    @Test fun case05() { verify(p("상품 구매\n판매가 139,000원").date == null, "no date fabrication") }
    @Test fun case06() { verify(p("COFFEE COUPON\nExpires: Sep 30, 2026").date == "2026-09-30", "English date") }
    @Test fun case07() { verify(p("쿠폰\n유효기간 2026.09.01 ~ 09.30").date == "2026-09-30", "range end") }
    @Test fun case08() { verify(p("행사\n일시 2026.09.25 ~ 2026.09.27").date == "2026-09-25", "event start") }
    @Test fun case09() { verify(p("쿠폰\n유효기간 2025.09.30").date == "2025-09-30", "past year remains past") }
    @Test fun case10() { verify(p("쿠폰\n유효기간 9월 30일").warning!!.contains("연도"), "yearless warning") }
    @Test fun case11() { verify(p("메모\n금액 $9.30").date == null, "decimal price not date") }
    @Test fun case12() { verify(p("쿠폰\n유효기간 2026.02.30").date == null, "invalid date") }
    @Test fun case13() { verify(CaptureParser.parseDateStrict("2024-02-29") != null, "leap year") }
    @Test fun case14() { verify(CaptureParser.parseDateStrict("2025-02-29") == null, "invalid leap year") }
    @Test fun case15() { verify(CaptureParser.parseDateStrict("2026-9-1") == null, "strict formatting") }
    @Test fun case16() { verify(CaptureParser.safeUrl("javascript:alert(1)") == null, "unsafe scheme") }
    @Test fun case17() { verify(CaptureParser.safeUrl("https://user:pass@example.com") == null, "URL credentials") }
    @Test fun case18() { verify(CaptureParser.safeUrl("https://example.com/path?q=hello") != null, "safe URL") }
    @Test fun case19() { verify(p("저장한 페이지\nhttps://example.com/a").url == "https://example.com/a", "link extraction") }
    @Test fun case20() { verify(p("카페 모퉁이\n주소: 서울 성동구 연무장길 10").category == Category.PLACE, "place") }
    @Test fun case21() { verify(p("카페 모퉁이\n주소: 서울 성동구 연무장길 10").location!!.contains("성동구"), "address") }
    @Test fun case22() { verify(p("9:41\n5G\n커피 쿠폰\n유효기간 2026.09.30").title == "커피 쿠폰", "status bar ignored") }
    @Test fun case23() { verify(p("").title == "새 스크린샷", "empty OCR preserved") }
    @Test fun case24() { verify(p("메모만 있습니다").date == null, "no invented date") }
    @Test fun case25() { verify(Capture(title="쿠폰",text="",date="2026-09-30").dueLabel(today) == "날짜 확인 필요", "confirmation gate") }
    @Test fun case26() { verify(!Capture(title="완료",text="",date="2026-09-30",done=true).isUpcoming(today), "completed hidden") }
    @Test fun case27() { verify(!Capture(title="지남",text="",date="2026-09-20").isUpcoming(today), "expired hidden") }
    @Test fun case28() { verify(Capture(title="오늘",text="",date="2026-09-21",dateConfirmed=true).dueLabel(today) == "오늘", "today") }
    @Test fun case29() { verify(CaptureParser.parse("쿠폰 유효기간 1월 5일", LocalDate.of(2026,12,31)).date == "2026-01-05", "no silent year rollover") }
}
