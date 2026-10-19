package com.shakenokirimi12.uoa_app.data.models;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * CampusSquare「単位修得状況照会」(SIW0001300) の先頭に出る集計。
 * GPA はアプリで計算せず、大学が出している値をそのまま使う (2026-09-27: 自前計算が実際の値と合わなかった)。
 */
public class CreditSummary {
    /** 累積ＧＰＡ。表示が無ければ null。 */
    @Nullable public Double cumulativeGpa;
    /** 修得単位数。卒業要件以外の単位も含む (CampusSquare の注記どおり)。 */
    @Nullable public Double earnedCredits;
    /** 年度・学期ごとの GPA。古い順。 */
    public List<TermGpa> terms = new ArrayList<>();

    public static class TermGpa {
        public String year;
        public String term;
        public double gpa;

        public TermGpa() {}

        public TermGpa(String year, String term, double gpa) {
            this.year = year;
            this.term = term;
            this.gpa = gpa;
        }
    }

    public boolean isEmpty() {
        return cumulativeGpa == null && earnedCredits == null && terms.isEmpty();
    }
}
