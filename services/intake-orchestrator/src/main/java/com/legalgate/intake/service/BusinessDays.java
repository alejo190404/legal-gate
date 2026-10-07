package com.legalgate.intake.service;

import static java.time.temporal.TemporalAdjusters.nextOrSame;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Business Day calendar: a weekday that is not a Colombian public holiday. Every promise
 * LegalGate counts in days — Urgency SLAs and Turnarounds — goes through here.
 *
 * <p>Holidays follow Ley 51 de 1983 by rule, so no year needs listing: fixed dates, dates moved to
 * the following Monday (Emiliani), and dates relative to Easter.
 */
public final class BusinessDays {

    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private static final Map<Integer, Set<LocalDate>> HOLIDAYS = new ConcurrentHashMap<>();

    private BusinessDays() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !HOLIDAYS.computeIfAbsent(date.getYear(), BusinessDays::holidays).contains(date);
    }

    public static LocalDate add(LocalDate start, int days) {
        LocalDate date = start;
        for (int remaining = Math.max(0, days); remaining > 0; ) {
            date = date.plusDays(1);
            if (isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }

    /** Adds Business Days in Bogota, keeping the time of day. */
    public static Instant add(Instant start, int days) {
        ZonedDateTime dateTime = start.atZone(ZONE);
        return dateTime.with(add(dateTime.toLocalDate(), days)).toInstant();
    }

    /** Business Days after {@code from} up to and including {@code to}; negative when {@code to} is earlier. */
    public static int between(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return -between(to, from);
        }
        int count = 0;
        for (LocalDate date = from.plusDays(1); !date.isAfter(to); date = date.plusDays(1)) {
            if (isBusinessDay(date)) {
                count++;
            }
        }
        return count;
    }

    private static Set<LocalDate> holidays(int year) {
        LocalDate easter = easter(year);
        return Stream.of(
                LocalDate.of(year, 1, 1),
                monday(LocalDate.of(year, 1, 6)),
                monday(LocalDate.of(year, 3, 19)),
                easter.minusDays(3),
                easter.minusDays(2),
                LocalDate.of(year, 5, 1),
                monday(easter.plusDays(39)),
                monday(easter.plusDays(60)),
                monday(easter.plusDays(68)),
                monday(LocalDate.of(year, 6, 29)),
                LocalDate.of(year, 7, 20),
                LocalDate.of(year, 8, 7),
                monday(LocalDate.of(year, 8, 15)),
                monday(LocalDate.of(year, 10, 12)),
                monday(LocalDate.of(year, 11, 1)),
                monday(LocalDate.of(year, 11, 11)),
                LocalDate.of(year, 12, 8),
                LocalDate.of(year, 12, 25)
        ).collect(Collectors.toUnmodifiableSet());
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(nextOrSame(DayOfWeek.MONDAY));
    }

    /** Anonymous Gregorian algorithm. */
    private static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
