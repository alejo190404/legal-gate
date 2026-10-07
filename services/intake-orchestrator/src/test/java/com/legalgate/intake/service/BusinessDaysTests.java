package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class BusinessDaysTests {

    @Test
    void knownColombianHolidaysAreNotBusinessDays() {
        List<LocalDate> holidays = List.of(
                // 2024: Easter on 31 March.
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 8), LocalDate.of(2024, 3, 25),
                LocalDate.of(2024, 3, 28), LocalDate.of(2024, 3, 29), LocalDate.of(2024, 5, 1),
                LocalDate.of(2024, 5, 13), LocalDate.of(2024, 6, 3), LocalDate.of(2024, 6, 10),
                LocalDate.of(2024, 7, 1), LocalDate.of(2024, 8, 7), LocalDate.of(2024, 8, 19),
                LocalDate.of(2024, 10, 14), LocalDate.of(2024, 11, 4), LocalDate.of(2024, 11, 11),
                LocalDate.of(2024, 12, 25),
                // 2025: Easter on 20 April.
                LocalDate.of(2025, 1, 6), LocalDate.of(2025, 3, 24), LocalDate.of(2025, 4, 17),
                LocalDate.of(2025, 4, 18), LocalDate.of(2025, 6, 2), LocalDate.of(2025, 6, 23),
                LocalDate.of(2025, 6, 30), LocalDate.of(2025, 8, 18), LocalDate.of(2025, 10, 13),
                LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 17), LocalDate.of(2025, 12, 8),
                // 2026: Easter on 5 April.
                LocalDate.of(2026, 1, 12), LocalDate.of(2026, 3, 23), LocalDate.of(2026, 4, 2),
                LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 18), LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 16));

        assertThat(holidays).noneMatch(BusinessDays::isBusinessDay);
    }

    @Test
    void ordinaryWeekdaysAreBusinessDaysAndWeekendsAreNot() {
        // The un-moved dates of Monday-moved holidays are ordinary days.
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2025, 11, 11))).isTrue();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 1, 6))).isTrue();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 7))).isTrue();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 10))).isFalse();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 11))).isFalse();
    }

    @Test
    void addingBusinessDaysSkipsWeekendsAndHolidays() {
        // Friday 9 Oct 2026; Monday 12 Oct is Dia de la Raza.
        assertThat(BusinessDays.add(LocalDate.of(2026, 10, 9), 1)).isEqualTo(LocalDate.of(2026, 10, 13));
        // Holy week 2026: Thursday 2 and Friday 3 April.
        assertThat(BusinessDays.add(LocalDate.of(2026, 3, 31), 3)).isEqualTo(LocalDate.of(2026, 4, 7));
        assertThat(BusinessDays.add(LocalDate.of(2026, 3, 31), 0)).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void anSlaDeadlineKeepsItsTimeOfDayAndSkipsAFestivo() {
        ZonedDateTime friday = ZonedDateTime.of(LocalDateTime.of(2026, 10, 9, 10, 30), BusinessDays.ZONE);

        assertThat(BusinessDays.add(friday.toInstant(), 1))
                .isEqualTo(friday.plusDays(4).toInstant());
    }

    @Test
    void countsBusinessDaysBetweenTwoDates() {
        assertThat(BusinessDays.between(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 13))).isEqualTo(1);
        assertThat(BusinessDays.between(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9))).isZero();
        assertThat(BusinessDays.between(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 9))).isEqualTo(-1);
    }
}
