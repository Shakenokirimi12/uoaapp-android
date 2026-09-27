package com.shakenokirimi12.uoa_app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.shakenokirimi12.uoa_app.data.models.CreditSummary;

import org.junit.Test;

/** 単位修得状況照会 (SIW0001300) の実画面 (2026-09-27、氏名・学籍番号は伏せた) と同じ構造の HTML で確かめる。 */
public class CreditSummaryParseTest {
    private static final String HEADER =
            "<table id=\"auto-table-1\" class=\"auto-table-1 sp-table-none\"><tbody>"
            + "<tr class=\"seiseki\"><th class=\"seiseki-head\">氏名</th><td>氏名</td><th class=\"seiseki-head\">学籍番号</th><td>s0000000</td></tr>"
            + "<tr class=\"seiseki\"><th class=\"seiseki-head\">所属</th><td>コンピュータ理工学部</td><th class=\"seiseki-head\">学年</th><td>1年</td></tr>"
            + "<tr class=\"seiseki\"><th class=\"seiseki-head\">年度・学期</th><td> 指示なし </td><th class=\"seiseki-head\">累積ＧＰＡ</th><td> 3.35 </td></tr>"
            + "<tr class=\"seiseki\"><th class=\"seiseki-head\">修得単位数</th><td> 26.0 注意）卒業要件以外の単位数も含みます </td></tr>"
            + "</tbody></table>";

    @Test
    public void readsCumulativeGpaCreditsAndTerms() {
        String html = HEADER
                + "<table id=\"auto-table-2\"><tbody>"
                + "<tr><th>年度</th><td>2026</td></tr>"
                + "<tr><th>１学期</th><td>3.76</td></tr>"
                + "<tr><th>２学期</th><td>3.00</td></tr>"
                + "</tbody></table>"
                // 科目一覧の見出しに「単位数」があっても集計と取り違えない
                + "<table><tr><th>No.</th><th>科目</th><th>単位数</th></tr><tr><td>1</td><td>社会学</td><td>2.0</td></tr></table>";
        CreditSummary s = CampusSquareService.parseCreditSummary(html);
        assertEquals(3.35, s.cumulativeGpa, 1e-9);
        assertEquals(26.0, s.earnedCredits, 1e-9);
        assertEquals(2, s.terms.size());
        assertEquals("2026", s.terms.get(0).year);
        assertEquals("１学期", s.terms.get(0).term);
        assertEquals(3.76, s.terms.get(0).gpa, 1e-9);
        assertEquals(3.00, s.terms.get(1).gpa, 1e-9);
    }

    @Test
    public void multipleYears_becomeColumns_andBlankCellsAreSkipped() {
        String html = HEADER
                + "<table><tbody>"
                + "<tr><th>年度</th><td>2025</td><td>2026</td></tr>"
                + "<tr><th>１学期</th><td>3.10</td><td>3.76</td></tr>"
                + "<tr><th>２学期</th><td>2.90</td><td></td></tr>"
                + "</tbody></table>";
        CreditSummary s = CampusSquareService.parseCreditSummary(html);
        assertEquals(3, s.terms.size());
        assertEquals("2025", s.terms.get(0).year);
        assertEquals("2025", s.terms.get(1).year);
        assertEquals("2026", s.terms.get(2).year);
        assertEquals(3.76, s.terms.get(2).gpa, 1e-9);
    }

    @Test
    public void unrelatedPage_isEmpty() {
        CreditSummary s = CampusSquareService.parseCreditSummary("<html><body>認証エラー</body></html>");
        assertTrue(s.isEmpty());
        assertNull(s.cumulativeGpa);
    }
}
