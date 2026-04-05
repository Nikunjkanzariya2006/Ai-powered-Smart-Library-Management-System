package com.nik.ai.tools;

import com.nik.ai.service.AiLibrarianContextQueryService;
import com.nik.exception.AiServiceException;
import com.nik.payload.dto.BookDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
public class AiLibrarianTools {

    private final AiLibrarianContextQueryService contextQueryService;

    @Tool(description = "Search books from the library catalog by title, author, ISBN, or genre.")
    public List<BookDTO> searchCatalog(
            @ToolParam(description = "The search query such as a title, author, ISBN, or topic") String query,
            @ToolParam(description = "Whether only available books should be returned") Boolean availableOnly,
            @ToolParam(description = "Maximum number of books to return between 1 and 10") Integer limit) {
        return contextQueryService.searchCatalog(query, limit, availableOnly);
    }

    @Tool(description = "Fetch detailed information for a single book using its book id.")
    public BookDTO getBookDetails(@ToolParam(description = "The unique id of the book") Long bookId) {
        return contextQueryService.getBookDetails(bookId);
    }

    @Tool(description = "Fetch an AI-generated overall review summary for a book using its ISBN, title, or close title match.")
    public Map<String, Object> getBookReviewSummary(
            @ToolParam(description = "The ISBN or book title to summarize reviews for") String queryOrIsbn) {
        return contextQueryService.getBookReviewSummary(queryOrIsbn);
    }

    @Tool(description = "Fetch a compact overview of the current user's loans, reservations, fines, and subscription using live database queries.")
    public Map<String, Object> getMyLibrarySnapshot(ToolContext toolContext) {
        Long userId = getUserId(toolContext);
        try {
            return contextQueryService.getMyLibrarySnapshot();
        } catch (Exception ex) {
            log.warn("Unable to fetch complete library snapshot for user {}", userId, ex);
            return Map.of("error", "Unable to fetch live library snapshot right now.");
        }
    }

    @Tool(description = "Fetch a compact overview of the current user's loans.")
    public Map<String, Object> getMyLoanSnapshot(ToolContext toolContext) {
        return contextQueryService.getMyLoanSnapshot(getUserId(toolContext));
    }

    @Tool(description = "Fetch a compact overview of the current user's reservations.")
    public Map<String, Object> getMyReservationSnapshot(ToolContext toolContext) {
        return contextQueryService.getMyReservationSnapshot(getUserId(toolContext));
    }

    @Tool(description = "Fetch a compact overview of the current user's fines and outstanding balances.")
    public Map<String, Object> getMyFineSnapshot(ToolContext toolContext) {
        return contextQueryService.getMyFineSnapshot(getUserId(toolContext));
    }

    @Tool(description = "Fetch the current user's active subscription and borrowing limits.")
    public Map<String, Object> getMySubscriptionSnapshot(ToolContext toolContext) {
        return contextQueryService.getMySubscriptionSnapshot(getUserId(toolContext));
    }

    @Tool(description = "Fetch the current user's wishlist summary and show which wishlist books are currently available.")
    public Map<String, Object> getMyWishlistSnapshot(ToolContext toolContext) {
        return contextQueryService.getMyWishlistSnapshot(getUserId(toolContext));
    }

    @Tool(description = "Fetch the current user's basic profile summary such as name, email, phone, verification status, and member since date.")
    public Map<String, Object> getMyProfileSummary() {
        return contextQueryService.getMyProfileSummary();
    }

    @Tool(description = "Compare active subscription plans including price, duration, monthly equivalent price, and borrowing limits.")
    public Map<String, Object> compareSubscriptionPlans() {
        return contextQueryService.getSubscriptionPlansComparison();
    }

    @Tool(description = "Fetch the current user's review history for books they reviewed.")
    public Map<String, Object> getMyReviewHistory(ToolContext toolContext) {
        return contextQueryService.getMyReviewHistory(getUserId(toolContext));
    }

    @Tool(description = "Fetch the current user's reading history based on returned books.")
    public Map<String, Object> getMyReadingHistory(ToolContext toolContext) {
        return contextQueryService.getMyReadingHistory(getUserId(toolContext));
    }

    @Tool(description = "Fetch a dashboard-style summary of the current user's profile, loans, fines, reservations, reviews, wishlist, and subscription.")
    public Map<String, Object> getDashboardSummary() {
        return contextQueryService.getDashboardSummary();
    }

    @Tool(description = "Fetch recommended books for the current user based on wishlist, reading history, and reviews.")
    public Map<String, Object> getRecommendedBooks() {
        return contextQueryService.getRecommendedBooks();
    }

    @Tool(description = "Fetch admin-side library analytics such as total users, active books, checkouts, fines, subscriptions, and monthly revenue.")
    public Map<String, Object> getAdminAnalyticsSummary() {
        return contextQueryService.getAdminAnalyticsSummary();
    }

    @Tool(description = "Fetch a troubleshooting guide for system issues such as password reset, login, reservations, payments, fines, notifications, or recommendation problems.")
    public Map<String, Object> getSystemSupportGuide(
            @ToolParam(description = "The reported issue type or symptom such as password reset, login issue, reservation problem, payment issue, fine issue, recommendation issue, or notification issue") String issueType) {
        return contextQueryService.getSystemSupportGuide(issueType);
    }

    @Tool(description = "Explain whether the current user has an active membership and what it allows.")
    public String getMembershipEligibilityStatus(ToolContext toolContext) {
        return contextQueryService.getMembershipEligibilityStatus(getUserId(toolContext));
    }

    private Long getUserId(ToolContext toolContext) {
        Object userId = toolContext.getContext().get("userId");
        if (userId instanceof Number number) {
            return number.longValue();
        }
        throw new AiServiceException("AI tool context is missing the authenticated user");
    }
}
