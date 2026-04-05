package com.nik.ai.service;

import com.nik.domain.BookLoanStatus;
import com.nik.domain.ReservationStatus;
import com.nik.domain.UserRole;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.SubscriptionException;
import com.nik.exception.UserException;
import com.nik.mapper.BookMapper;
import com.nik.mapper.FineMapper;
import com.nik.mapper.ReservationMapper;
import com.nik.mapper.SubscriptionMapper;
import com.nik.model.BookReview;
import com.nik.model.Book;
import com.nik.model.BookLoan;
import com.nik.model.Subscription;
import com.nik.model.SubscriptionPlan;
import com.nik.model.User;
import com.nik.model.Wishlist;
import com.nik.payload.dto.BookDTO;
import com.nik.payload.dto.BookReviewDTO;
import com.nik.payload.dto.FineDTO;
import com.nik.payload.dto.ReservationDTO;
import com.nik.payload.dto.SubscriptionDTO;
import com.nik.payload.dto.SubscriptionPlanDTO;
import com.nik.payload.request.BookSearchRequest;
import com.nik.payload.response.BookReviewAiSummaryResponse;
import com.nik.payload.response.PageResponse;
import com.nik.payload.response.PersonalizedRecommendationsResponse;
import com.nik.payload.response.RevenueStatisticsResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.BookReviewRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.ReservationRepository;
import com.nik.repository.SubscriptionRepository;
import com.nik.repository.SubscriptionPlanRepository;
import com.nik.repository.WishlistRepository;
import com.nik.ai.service.AiBookReviewSummaryService;
import com.nik.ai.service.RecommendationService;
import com.nik.service.BookService;
import com.nik.service.PaymentService;
import com.nik.service.SubscriptionService;
import com.nik.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
public class AiLibrarianContextQueryService {

    private final UserService userService;
    private final BookService bookService;
    private final AiBookReviewSummaryService aiBookReviewSummaryService;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final BookLoanRepository bookLoanRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    private final FineRepository fineRepository;
    private final FineMapper fineMapper;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final SubscriptionMapper subscriptionMapper;
    private final WishlistRepository wishlistRepository;
    private final BookReviewRepository bookReviewRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final PaymentService paymentService;
    private final RecommendationService recommendationService;

    public List<BookDTO> searchCatalog(String query, Integer limit, Boolean availableOnly) {
        BookSearchRequest request = new BookSearchRequest();
        request.setSearchTerm(StringUtils.hasText(query) ? query.trim() : null);
        request.setAvailableOnly(Boolean.TRUE.equals(availableOnly));
        request.setActiveOnly(true);
        request.setPage(0);
        request.setSize(Math.min(Math.max(limit == null ? 5 : limit, 1), 10));
        request.setSortBy("title");
        request.setSortDirection("ASC");

        PageResponse<BookDTO> pageResponse = bookService.searchBooksWithFilters(request);
        return pageResponse.getContent();
    }

    public BookDTO getBookDetails(Long bookId) {
        Book book = bookRepository.findById(bookId).orElse(null);
        return book == null ? null : bookMapper.toDTO(book);
    }

    public Map<String, Object> getBookReviewSummary(String queryOrIsbn) {
        if (!StringUtils.hasText(queryOrIsbn)) {
            return Map.of("error", "Please provide a book title or ISBN to summarize reviews.");
        }

        String normalizedQuery = queryOrIsbn.trim();
        Optional<Book> exactIsbnMatch = bookRepository.findByIsbn(normalizedQuery);
        Book matchedBook;

        if (exactIsbnMatch.isPresent()) {
            matchedBook = exactIsbnMatch.get();
        } else {
            List<BookDTO> matches = searchCatalog(normalizedQuery, 5, false);
            if (matches.isEmpty()) {
                return Map.of("error", "No matching book found for the provided title or ISBN.");
            }
            matchedBook = bookRepository.findById(matches.get(0).getId()).orElse(null);
            if (matchedBook == null) {
                return Map.of("error", "A matching book was found, but details could not be loaded.");
            }
        }

        BookReviewAiSummaryResponse summary;
        try {
            summary = aiBookReviewSummaryService.generateSummary(matchedBook.getId());
        } catch (Exception ex) {
            return Map.of("error", "Unable to generate the review summary for the requested book right now.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookId", summary.getBookId());
        payload.put("bookTitle", summary.getBookTitle());
        payload.put("isbn", matchedBook.getIsbn());
        payload.put("sentimentLabel", summary.getSentimentLabel());
        payload.put("averageRating", summary.getAverageRating());
        payload.put("totalReviews", summary.getTotalReviews());
        payload.put("verifiedReaderReviews", summary.getVerifiedReaderReviews());
        payload.put("sampledReviews", summary.getSampledReviews());
        payload.put("summary", summary.getSummary());
        return payload;
    }

    public Map<String, Object> getMyLibrarySnapshot() {
        Long userId;
        try {
            userId = userService.getCurrentUser().getId();
        } catch (UserException ex) {
            throw new AuthenticationFailureException("Unable to resolve current user for AI Librarian context", ex);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("loans", getMyLoanSnapshot(userId));
        snapshot.put("reservations", getMyReservationSnapshot(userId));
        snapshot.put("fines", getMyFineSnapshot(userId));
        snapshot.put("subscription", getMySubscriptionSnapshot(userId));
        return snapshot;
    }

    public Map<String, Object> getMyProfileSummary() {
        User currentUser = getCurrentUserSafe();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", currentUser.getFullName());
        payload.put("email", currentUser.getEmail());
        payload.put("phone", currentUser.getPhone());
        payload.put("role", currentUser.getRole());
        payload.put("verified", currentUser.getVerified());
        payload.put("memberSince", currentUser.getCreatedAt());
        payload.put("lastLogin", currentUser.getLastLogin());
        return payload;
    }

    public Map<String, Object> getMyWishlistSnapshot(Long userId) {
        List<Wishlist> items = wishlistRepository.findByUserId(
                        userId,
                        PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "addedAt"))
                )
                .getContent();

        long totalCount = wishlistRepository.countByUserId(userId);
        long availableCount = wishlistRepository.countAvailableByUserId(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCount", totalCount);
        payload.put("availableCount", availableCount);
        payload.put("items", items.stream().map(this::toWishlistMap).toList());
        return payload;
    }

    public Map<String, Object> getSubscriptionPlansComparison() {
        List<SubscriptionPlanDTO> plans = subscriptionPlanRepository.findAllActivePlans()
                .stream()
                .map(this::toSubscriptionPlanDto)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activePlanCount", plans.size());
        payload.put("plans", plans.stream().map(this::toSubscriptionPlanMap).toList());
        return payload;
    }

    public Map<String, Object> getMyReviewHistory(Long userId) {
        PageRequest pageRequest = PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "createdAt"));
        var reviewsPage = bookReviewRepository.findByUserIdAndIsActiveTrue(userId, pageRequest);
        List<BookReviewDTO> reviews = reviewsPage.getContent()
                .stream()
                .map(this::toReviewDto)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalReviews", bookReviewRepository.countByUserIdAndIsActiveTrue(userId));
        payload.put("items", reviews.stream().map(this::toReviewMap).toList());
        return payload;
    }

    public Map<String, Object> getMyReadingHistory(Long userId) {
        User currentUser = getCurrentUserSafe();
        List<BookLoan> history = bookLoanRepository.findByStatusAndUser(
                        BookLoanStatus.RETURNED,
                        currentUser,
                        PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "returnDate"))
                )
                .getContent();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalReturnedBooks", bookLoanRepository.countByStatusAndUser(BookLoanStatus.RETURNED, currentUser));
        payload.put("items", history.stream().map(this::toReadingHistoryMap).toList());
        return payload;
    }

    public Map<String, Object> getDashboardSummary() {
        User currentUser = getCurrentUserSafe();
        Long userId = currentUser.getId();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", getMyProfileSummary());
        payload.put("wishlist", getMyWishlistSnapshot(userId));
        payload.put("loans", getMyLoanSnapshot(userId));
        payload.put("reservations", getMyReservationSnapshot(userId));
        payload.put("fines", getMyFineSnapshot(userId));
        payload.put("subscription", getMySubscriptionSnapshot(userId));
        payload.put("reviews", getMyReviewHistory(userId));
        return payload;
    }

    public Map<String, Object> getSystemSupportGuide(String issueType) {
        String normalizedIssue = issueType == null ? "" : issueType.trim().toLowerCase();
        User currentUser = getCurrentUserSafe();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issueType", normalizedIssue.isBlank() ? "general" : normalizedIssue);
        payload.put("userEmail", currentUser.getEmail());
        payload.put("isVerified", currentUser.getVerified());

        if (matchesIssue(normalizedIssue, "password", "reset", "forgot password")) {
            payload.put("category", "password-reset");
            payload.put("likelyCause", "Password reset works through an emailed token link, and the token expires quickly.");
            payload.put("knownBehavior", List.of(
                    "The reset email link points to /auth/reset-password with a token query parameter.",
                    "The reset token is valid for 5 minutes.",
                    "The user must open the latest reset email link and submit a new password from that screen."
            ));
            payload.put("steps", List.of(
                    "Open the latest password reset email only. Older emails may contain expired tokens.",
                    "Click the reset link and confirm that the browser URL contains /auth/reset-password?token=...",
                    "Enter a new password and confirm it on the reset screen.",
                    "If the token has expired, request a fresh forgot-password email and use the newest link."
            ));
            payload.put("checks", List.of(
                    "If the link opens the wrong page, refresh the frontend and retry with a newly generated email.",
                    "If reset still fails, check whether the token is missing from the URL.",
                    "Never share the reset token in chat."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "login", "signin", "sign in", "log in")) {
            payload.put("category", "login");
            payload.put("likelyCause", "Most login problems come from wrong credentials, using a different email, or trying before password reset is completed.");
            payload.put("knownBehavior", List.of(
                    "Login requires the account email and the current password.",
                    "After a successful password reset, only the new password works.",
                    "The chatbot itself is available only after authentication."
            ));
            payload.put("steps", List.of(
                    "Confirm the exact email used for the library account.",
                    "If you recently changed your password, log in with the new password only.",
                    "If login still fails, use forgot password to generate a fresh reset link.",
                    "If you are already signed in on another device, confirm the same account email there."
            ));
            payload.put("checks", List.of(
                    "Avoid using cached old passwords from the browser password manager.",
                    "If the app keeps redirecting, clear the stale session token and try again."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "reservation", "reserve", "hold")) {
            payload.put("category", "reservation");
            payload.put("likelyCause", "Reservation visibility issues usually come from queue status, expiry, or availability changes.");
            payload.put("userSnapshot", getMyReservationSnapshot(currentUser.getId()));
            payload.put("steps", List.of(
                    "Check your active reservations and queue position first.",
                    "If a reservation became available, verify the available-until time.",
                    "If a reservation is missing, refresh the reservations page and re-check status."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "payment", "subscription", "checkout")) {
            payload.put("category", "payment");
            payload.put("likelyCause", "Payment or subscription issues usually come from an incomplete payment flow, callback interruption, or plan mismatch.");
            payload.put("userSnapshot", getMySubscriptionSnapshot(currentUser.getId()));
            payload.put("steps", List.of(
                    "Check whether your active subscription is visible in the app.",
                    "If payment completed but the plan is not updated, refresh once and re-open subscriptions.",
                    "If the issue continues, keep the payment reference or screenshot ready for admin support."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "fine", "fines", "penalty")) {
            payload.put("category", "fine");
            payload.put("likelyCause", "Fine confusion usually comes from unpaid balances, overdue returns, or recently processed payments.");
            payload.put("userSnapshot", getMyFineSnapshot(currentUser.getId()));
            payload.put("steps", List.of(
                    "Check whether you currently have unpaid fines.",
                    "Review the recent fine records and their status.",
                    "If a payment was just made, refresh and verify whether the outstanding amount changed."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "recommend", "recommendation", "dashboard", "suggested books")) {
            payload.put("category", "recommendations");
            payload.put("likelyCause", "Recommendations depend on reading history, wishlist, and reviews. If those signals are weak, results may be limited or repetitive.");
            payload.put("userSnapshot", getDashboardSummary());
            payload.put("steps", List.of(
                    "Add wishlist books or reviews to strengthen personalization.",
                    "Return or review more books so the system has more reading signals.",
                    "Refresh the dashboard after new activity is recorded."
            ));
            return payload;
        }

        if (matchesIssue(normalizedIssue, "notification", "notifications", "email")) {
            payload.put("category", "notifications");
            payload.put("likelyCause", "Notification issues usually come from delivery delays, settings, or mailbox filtering.");
            payload.put("steps", List.of(
                    "Check spam or promotions folders for library emails.",
                    "Wait briefly and retry the triggering action once.",
                    "If the issue is about account mail, confirm your current profile email."
            ));
            payload.put("userSnapshot", getMyProfileSummary());
            return payload;
        }

        payload.put("category", "general");
        payload.put("likelyCause", "The issue needs a quick classification first.");
        payload.put("supportedIssueTypes", List.of(
                "password reset",
                "login",
                "reservation",
                "payment or subscription",
                "fine",
                "recommendation",
                "notification"
        ));
        payload.put("steps", List.of(
                "Describe what you clicked and what happened instead.",
                "Mention the page or feature name.",
                "If there was an error message, include its exact text."
        ));
        return payload;
    }

    public Map<String, Object> getRecommendedBooks() {
        try {
            PersonalizedRecommendationsResponse response =
                    recommendationService.getPersonalizedRecommendations(8, System.currentTimeMillis());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("strategy", response.getStrategy());
            payload.put("borrowHistoryCount", response.getBorrowHistoryCount());
            payload.put("wishlistCount", response.getWishlistCount());
            payload.put("reviewCount", response.getReviewCount());
            payload.put("items", response.getItems().stream().map(item -> {
                Map<String, Object> data = toRecommendedBookMap(item.getBook());
                data.put("score", item.getScore());
                data.put("reason", item.getReason());
                return data;
            }).toList());
            return payload;
        } catch (UserException ex) {
            throw new AuthenticationFailureException("Unable to resolve current user for AI Librarian recommendations", ex);
        }
    }

    public Map<String, Object> getAdminAnalyticsSummary() {
        User currentUser = getCurrentUserSafe();
        if (currentUser.getRole() != UserRole.ROLE_ADMIN) {
            return Map.of("error", "Admin analytics are available only to admin users.");
        }

        RevenueStatisticsResponse revenue = paymentService.getMonthlyRevenue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalUsers", userService.getTotalUserCount());
        payload.put("totalActiveBooks", bookRepository.countByActiveTrue());
        payload.put("totalAvailableBooks", bookRepository.countAvailableBooks());
        payload.put("activeCheckouts", bookLoanRepository.countActiveBookLoans());
        payload.put("overdueCheckouts", bookLoanRepository.countOverdueBookLoans(LocalDate.now()));
        payload.put("returnedBooks", bookLoanRepository.countByStatus(BookLoanStatus.RETURNED));
        payload.put("outstandingFines", fineRepository.getTotalOutstandingFines());
        payload.put("collectedFines", fineRepository.getTotalCollectedFines());
        payload.put("activeSubscriptionPlans", subscriptionPlanRepository.countActivePlans());
        payload.put("activeSubscriptions", subscriptionRepository.findAllActiveSubscriptions(LocalDate.now()).size());
        payload.put("monthlyRevenue", revenue.getMonthlyRevenue());
        payload.put("monthlyRevenueCurrency", revenue.getCurrency());
        return payload;
    }

    public Map<String, Object> getMyLoanSnapshot(Long userId) {
        long activeCount = bookLoanRepository.countActiveBookLoansByUser(userId);
        long overdueCount = bookLoanRepository.countOverdueBookLoansByUser(userId, LocalDate.now());
        List<Map<String, Object>> recentLoans = bookLoanRepository
                .findByUserId(userId, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent()
                .stream()
                .map(this::toLoanMap)
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeCount", activeCount);
        payload.put("overdueCount", overdueCount);
        payload.put("recentLoans", recentLoans);
        return payload;
    }

    public Map<String, Object> getMyReservationSnapshot(Long userId) {
        List<ReservationDTO> activeReservations = reservationRepository.searchReservationsWithFilters(
                        userId,
                        null,
                        null,
                        true,
                        List.of(ReservationStatus.PENDING, ReservationStatus.AVAILABLE),
                        true,
                        false,
                        null,
                        false,
                        null,
                        PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "reservedAt"))
                )
                .getContent()
                .stream()
                .map(reservationMapper::toDTO)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeCount", reservationRepository.countActiveReservationsByUser(userId));
        payload.put("items", activeReservations.stream().map(this::toReservationMap).toList());
        return payload;
    }

    public Map<String, Object> getMyFineSnapshot(Long userId) {
        List<FineDTO> fines = fineRepository.findByUserIdAndOptionalFilters(userId, null, null)
                .stream()
                .limit(5)
                .map(fineMapper::toDTO)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hasUnpaidFines", fineRepository.hasUnpaidFines(userId));
        payload.put("totalOutstanding", fineRepository.getTotalUnpaidFinesByUserId(userId));
        payload.put("recentFines", fines.stream().map(this::toFineMap).toList());
        return payload;
    }

    public Map<String, Object> getMySubscriptionSnapshot(Long userId) {
        Optional<Subscription> activeSubscription = subscriptionRepository.findActiveSubscriptionByUserId(userId, LocalDate.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (activeSubscription.isPresent()) {
            SubscriptionDTO dto = subscriptionMapper.toDTO(activeSubscription.get());
            payload.put("active", true);
            payload.put("details", toSubscriptionMap(dto));
        } else {
            payload.put("active", false);
            payload.put("details", null);
        }
        return payload;
    }

    public String getMembershipEligibilityStatus(Long userId) {
        try {
            SubscriptionDTO subscriptionDTO = subscriptionService.getUsersActiveSubscription(userId);
            return "Active plan " + subscriptionDTO.getPlanName()
                    + " allows " + subscriptionDTO.getMaxBooksAllowed()
                    + " concurrent books for " + subscriptionDTO.getMaxDaysPerBook()
                    + " days each.";
        } catch (SubscriptionException | UserException ex) {
            return "No active subscription found for this user.";
        }
    }

    private Map<String, Object> toLoanMap(BookLoan loan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", loan.getBook().getId());
        data.put("title", loan.getBook().getTitle());
        data.put("status", loan.getStatus());
        data.put("checkoutDate", loan.getCheckoutDate());
        data.put("dueDate", loan.getDueDate());
        data.put("returnDate", loan.getReturnDate());
        data.put("overdueDays", loan.getOverdueDays());
        return data;
    }

    private Map<String, Object> toReservationMap(ReservationDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", dto.getBookId());
        data.put("bookTitle", dto.getBookTitle());
        data.put("status", dto.getStatus());
        data.put("queuePosition", dto.getQueuePosition());
        data.put("availableUntil", dto.getAvailableUntil());
        data.put("hoursUntilExpiry", dto.getHoursUntilExpiry());
        return data;
    }

    private Map<String, Object> toFineMap(FineDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fineId", dto.getId());
        data.put("bookTitle", dto.getBookTitle());
        data.put("type", dto.getType());
        data.put("status", dto.getStatus());
        data.put("amountOutstanding", dto.getAmountOutstanding());
        data.put("paidAt", dto.getPaidAt());
        return data;
    }

    private Map<String, Object> toSubscriptionMap(SubscriptionDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planName", dto.getPlanName());
        data.put("isValid", dto.getIsValid());
        data.put("isExpired", dto.getIsExpired());
        data.put("daysRemaining", dto.getDaysRemaining());
        data.put("maxBooksAllowed", dto.getMaxBooksAllowed());
        data.put("maxDaysPerBook", dto.getMaxDaysPerBook());
        data.put("price", dto.getPrice());
        data.put("currency", dto.getCurrency());
        return data;
    }

    private Map<String, Object> toWishlistMap(Wishlist wishlist) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", wishlist.getBook().getId());
        data.put("title", wishlist.getBook().getTitle());
        data.put("author", wishlist.getBook().getAuthor());
        data.put("availableCopies", wishlist.getBook().getAvailableCopies());
        data.put("isAvailable", wishlist.getBook().getAvailableCopies() != null && wishlist.getBook().getAvailableCopies() > 0);
        data.put("addedAt", wishlist.getAddedAt());
        data.put("notes", wishlist.getNotes());
        return data;
    }

    private BookReviewDTO toReviewDto(BookReview review) {
        return BookReviewDTO.builder()
                .id(review.getId())
                .userId(review.getUser().getId())
                .userName(review.getUser().getFullName())
                .bookId(review.getBook().getId())
                .bookTitle(review.getBook().getTitle())
                .rating(review.getRating())
                .reviewText(review.getReviewText())
                .title(review.getTitle())
                .isVerifiedReader(review.getIsVerifiedReader())
                .isActive(review.getIsActive())
                .helpfulCount(review.getHelpfulCount())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }

    private Map<String, Object> toReviewMap(BookReviewDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", dto.getBookId());
        data.put("bookTitle", dto.getBookTitle());
        data.put("rating", dto.getRating());
        data.put("title", dto.getTitle());
        data.put("isVerifiedReader", dto.getIsVerifiedReader());
        data.put("helpfulCount", dto.getHelpfulCount());
        data.put("createdAt", dto.getCreatedAt());
        return data;
    }

    private Map<String, Object> toReadingHistoryMap(BookLoan loan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", loan.getBook().getId());
        data.put("title", loan.getBook().getTitle());
        data.put("author", loan.getBook().getAuthor());
        data.put("checkoutDate", loan.getCheckoutDate());
        data.put("returnDate", loan.getReturnDate());
        data.put("renewalCount", loan.getRenewalCount());
        return data;
    }

    private SubscriptionPlanDTO toSubscriptionPlanDto(SubscriptionPlan plan) {
        return new SubscriptionPlanDTO(
                plan.getId(),
                plan.getPlanCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getDurationDays(),
                plan.getPrice(),
                plan.getCurrency(),
                plan.getMaxBooksAllowed(),
                plan.getMaxDaysPerBook(),
                plan.getDisplayOrder(),
                plan.getIsActive(),
                plan.getIsFeatured(),
                plan.getBadgeText(),
                plan.getAdminNotes(),
                plan.getPriceInMajorUnits(),
                plan.getMonthlyEquivalentPrice(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getCreatedBy(),
                plan.getUpdatedBy()
        );
    }

    private Map<String, Object> toSubscriptionPlanMap(SubscriptionPlanDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planCode", dto.getPlanCode());
        data.put("name", dto.getName());
        data.put("durationDays", dto.getDurationDays());
        data.put("price", dto.getPrice());
        data.put("priceInMajorUnits", dto.getPriceInMajorUnits());
        data.put("monthlyEquivalentPrice", dto.getMonthlyEquivalentPrice());
        data.put("maxBooksAllowed", dto.getMaxBooksAllowed());
        data.put("maxDaysPerBook", dto.getMaxDaysPerBook());
        data.put("badgeText", dto.getBadgeText());
        data.put("description", dto.getDescription());
        return data;
    }

    private Map<String, Object> toRecommendedBookMap(BookDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", dto.getId());
        data.put("title", dto.getTitle());
        data.put("author", dto.getAuthor());
        data.put("genreName", dto.getGenreName());
        data.put("availableCopies", dto.getAvailableCopies());
        data.put("description", dto.getDescription());
        return data;
    }

    private boolean matchesIssue(String normalizedIssue, String... keywords) {
        for (String keyword : keywords) {
            if (normalizedIssue.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private User getCurrentUserSafe() {
        try {
            return userService.getCurrentUser();
        } catch (UserException ex) {
            throw new AuthenticationFailureException("Unable to resolve current user for AI Librarian context", ex);
        }
    }
}


