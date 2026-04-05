package com.nik.mapper;

import com.nik.model.BookLoan;
import com.nik.payload.dto.BookLoanDTO;
import com.nik.repository.FineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Mapper for converting between BookLoan entity and BookLoanDTO
 */
@Component
@RequiredArgsConstructor
public class BookLoanMapper {

    private final FineRepository fineRepository;

    /**
     * Convert BookLoan entity to BookLoanDTO
     */
    public BookLoanDTO toDTO(BookLoan bookLoan) {
        if (bookLoan == null) {
            return null;
        }

        BookLoanDTO dto = new BookLoanDTO();
        dto.setId(bookLoan.getId());

        // User information
        if (bookLoan.getUser() != null) {
            dto.setUserId(bookLoan.getUser().getId());
            dto.setUserName(bookLoan.getUser().getFullName());
            dto.setUserEmail(bookLoan.getUser().getEmail());
        }

        // Book information
        if (bookLoan.getBook() != null) {
            dto.setBookId(bookLoan.getBook().getId());
            dto.setBookTitle(bookLoan.getBook().getTitle());
            dto.setBookIsbn(bookLoan.getBook().getIsbn());
            dto.setBookAuthor(bookLoan.getBook().getAuthor());
            dto.setBookCoverImage(bookLoan.getBook().getCoverImageUrl());
        }

        // Book loan details
        dto.setType(bookLoan.getType());
        dto.setStatus(bookLoan.getStatus());
        dto.setCheckoutDate(bookLoan.getCheckoutDate());
        dto.setDueDate(bookLoan.getDueDate());
        dto.setRemainingDays(
                    ChronoUnit.DAYS.between(
                            LocalDate.now(),
                    bookLoan.getDueDate()
                )
        );
        dto.setReturnDate(bookLoan.getReturnDate());
        dto.setRenewalCount(bookLoan.getRenewalCount());
        dto.setMaxRenewals(bookLoan.getMaxRenewals());
        long outstandingFineAmount = fineRepository.findByBookLoanId(bookLoan.getId()).stream()
                .filter(fine -> fine.isPending() && fine.getAmountOutstanding() > 0)
                .mapToLong(fine -> Math.max(0L, fine.getAmount() - fine.getAmountPaid()))
                .sum();
        dto.setFineAmount(BigDecimal.valueOf(outstandingFineAmount));
        dto.setFinePaid(outstandingFineAmount <= 0);

        dto.setNotes(bookLoan.getNotes());
        dto.setIsOverdue(bookLoan.getIsOverdue());
        dto.setOverdueDays(bookLoan.getOverdueDays());
        dto.setCreatedAt(bookLoan.getCreatedAt());
        dto.setUpdatedAt(bookLoan.getUpdatedAt());

        return dto;
    }
}

