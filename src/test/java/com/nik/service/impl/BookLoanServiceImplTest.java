package com.nik.service.impl;

import com.nik.domain.BookLoanStatus;
import com.nik.domain.BookLoanType;
import com.nik.domain.FineType;
import com.nik.mapper.BookLoanMapper;
import com.nik.model.Book;
import com.nik.model.BookLoan;
import com.nik.model.Fine;
import com.nik.model.User;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.ReservationRepository;
import com.nik.repository.UserRepository;
import com.nik.service.FineCalculationService;
import com.nik.service.ReservationQueueService;
import com.nik.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookLoanServiceImplTest {

    @Mock
    private BookLoanRepository bookLoanRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private FineRepository fineRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationQueueService reservationQueueService;

    private BookLoanServiceImpl bookLoanService;

    @BeforeEach
    void setUp() {
        BookLoanMapper bookLoanMapper = new BookLoanMapper(fineRepository);
        FineCalculationService fineCalculationService = new FineCalculationService();
        bookLoanService = new BookLoanServiceImpl(
                bookLoanRepository,
                bookRepository,
                userRepository,
                bookLoanMapper,
                fineCalculationService,
                subscriptionService,
                fineRepository,
                reservationRepository,
                reservationQueueService
        );

        when(bookLoanRepository.save(any(BookLoan.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void synchronizeLoanStateForUser_marksPastDueLoansOverdueAndCreatesFine() {
        BookLoan loan = createLoan(LocalDate.now().minusDays(3), BookLoanStatus.CHECKED_OUT);
        loan.setIsOverdue(false);
        loan.setOverdueDays(0);

        when(bookLoanRepository.findByUserIdAndStatusIn(eq(7L), anyCollection())).thenReturn(List.of(loan));
        when(fineRepository.findByBookLoanIdAndType(loan.getId(), FineType.OVERDUE)).thenReturn(List.of());
        when(fineRepository.save(any(Fine.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int updatedCount = bookLoanService.synchronizeLoanStateForUser(7L);

        assertEquals(1, updatedCount);
        assertEquals(BookLoanStatus.OVERDUE, loan.getStatus());
        assertTrue(Boolean.TRUE.equals(loan.getIsOverdue()));
        assertEquals(3, loan.getOverdueDays());

        ArgumentCaptor<Fine> fineCaptor = ArgumentCaptor.forClass(Fine.class);
        verify(fineRepository).save(fineCaptor.capture());
        Fine savedFine = fineCaptor.getValue();
        assertEquals(FineType.OVERDUE, savedFine.getType());
        assertEquals(3L, savedFine.getAmount());
        assertEquals(loan.getId(), savedFine.getBookLoan().getId());
    }

    @Test
    void synchronizeLoanStateForUser_clearsStaleOverdueFlagsWhenLoanIsNoLongerLate() {
        BookLoan loan = createLoan(LocalDate.now().plusDays(2), BookLoanStatus.OVERDUE);
        loan.setIsOverdue(true);
        loan.setOverdueDays(4);

        when(bookLoanRepository.findByUserIdAndStatusIn(eq(7L), anyCollection())).thenReturn(List.of(loan));

        int updatedCount = bookLoanService.synchronizeLoanStateForUser(7L);

        assertEquals(1, updatedCount);
        assertEquals(BookLoanStatus.CHECKED_OUT, loan.getStatus());
        assertFalse(Boolean.TRUE.equals(loan.getIsOverdue()));
        assertEquals(0, loan.getOverdueDays());
        verify(fineRepository, never()).save(any(Fine.class));
    }

    private BookLoan createLoan(LocalDate dueDate, BookLoanStatus status) {
        User user = new User();
        user.setId(7L);
        user.setEmail("reader@example.com");
        user.setFullName("Reader");

        Book book = new Book();
        book.setId(21L);
        book.setTitle("Sample Overdue Book");
        book.setAuthor("Test Author");
        book.setPrice(new BigDecimal("499"));

        BookLoan loan = new BookLoan();
        loan.setId(99L);
        loan.setUser(user);
        loan.setBook(book);
        loan.setType(BookLoanType.CHECKOUT);
        loan.setStatus(status);
        loan.setCheckoutDate(dueDate.minusDays(14));
        loan.setDueDate(dueDate);
        loan.setRenewalCount(0);
        loan.setMaxRenewals(2);
        return loan;
    }
}
