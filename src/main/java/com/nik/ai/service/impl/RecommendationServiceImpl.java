package com.nik.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nik.domain.BookLoanStatus;
import com.nik.exception.UserException;
import com.nik.mapper.BookMapper;
import com.nik.model.Book;
import com.nik.model.BookLoan;
import com.nik.model.BookReview;
import com.nik.model.Genre;
import com.nik.model.User;
import com.nik.model.Wishlist;
import com.nik.payload.dto.BookDTO;
import com.nik.payload.dto.RecommendedBookDTO;
import com.nik.payload.response.PersonalizedRecommendationsResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.BookReviewRepository;
import com.nik.repository.GenreRepository;
import com.nik.repository.WishlistRepository;
import com.nik.ai.service.RecommendationService;
import com.nik.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 8;
    private static final int AI_CANDIDATE_POOL_SIZE = 24;
    private static final int AI_SELECTION_WINDOW_SIZE = 16;
    private static final int BROAD_CANDIDATE_POOL_SIZE = 72;
    private static final int CURATED_CANDIDATE_TARGET = 40;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final long AI_TIMEOUT_SECONDS = 8;
    private static final int TOKEN_LIMIT_PER_BOOK = 12;
    private static final int MAX_SIGNAL_GENRES = 12;
    private static final int PER_GENRE_CANDIDATE_LIMIT = 4;

    private final UserService userService;
    private final WishlistRepository wishlistRepository;
    private final BookReviewRepository bookReviewRepository;
    private final BookLoanRepository bookLoanRepository;
    private final BookRepository bookRepository;
    private final GenreRepository genreRepository;
    private final BookMapper bookMapper;
    @Qualifier("recommendationChatClient")
    private final ChatClient recommendationChatClient;
    private final ObjectMapper objectMapper;
    private final Map<Long, CachedRecommendationSnapshot> recommendationCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public PersonalizedRecommendationsResponse getPersonalizedRecommendations(Integer limit, Long nonce) throws UserException {
        User currentUser = userService.getCurrentUser();
        int safeLimit = Math.min(Math.max(limit == null ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        long requestNonce = nonce != null ? nonce : System.currentTimeMillis();

        List<Wishlist> wishlistItems = wishlistRepository.findAllByUserId(currentUser.getId());
        List<BookReview> reviewItems = bookReviewRepository.findAllByUserIdAndIsActiveTrue(currentUser.getId());
        List<BookLoan> returnedLoanItems = bookLoanRepository.findByUserIdAndStatusIn(
                currentUser.getId(),
                List.of(BookLoanStatus.RETURNED)
        );
        List<BookLoan> activeLoanItems = bookLoanRepository.findByUserIdAndStatusIn(
                currentUser.getId(),
                List.of(BookLoanStatus.CHECKED_OUT, BookLoanStatus.OVERDUE)
        );

        Map<Long, Double> genreScores = new HashMap<>();
        Map<Long, GenreSignal> genreSignals = new HashMap<>();
        Set<Long> excludeBookIds = new HashSet<>();
        PreferenceProfile profile = new PreferenceProfile();
        int interestCount = currentUser.getInterestGenres() == null ? 0 : currentUser.getInterestGenres().size();

        if (currentUser.getInterestGenres() != null) {
            currentUser.getInterestGenres().forEach(genre -> {
                addGenreSignal(genreScores, genreSignals, genre.getId(), 2.5, SignalType.INTEREST);
                profile.registerInterestGenre(genre);
            });
        }

        wishlistItems.forEach(item -> {
            Book book = item.getBook();
            if (book == null || book.getGenre() == null) {
                return;
            }

            excludeBookIds.add(book.getId());
            addGenreSignal(genreScores, genreSignals, book.getGenre().getId(), 4.0, SignalType.WISHLIST);
            profile.registerBook(book, 4.0, SignalType.WISHLIST, RecommendationFactor.WISHLIST);
        });

        reviewItems.forEach(review -> {
            Book book = review.getBook();
            if (book == null || book.getGenre() == null) {
                return;
            }

            excludeBookIds.add(book.getId());
            double weight = switch (review.getRating()) {
                case 5 -> 5.0;
                case 4 -> 4.5;
                case 3 -> 2.0;
                case 2 -> -1.5;
                case 1 -> -3.0;
                default -> 0.0;
            };
            addGenreSignal(
                    genreScores,
                    genreSignals,
                    book.getGenre().getId(),
                    weight,
                    review.getRating() >= 4 ? SignalType.POSITIVE_REVIEW : SignalType.REVIEW
            );
            profile.registerBook(
                    book,
                    weight,
                    review.getRating() >= 4 ? SignalType.POSITIVE_REVIEW : SignalType.REVIEW,
                    RecommendationFactor.REVIEW
            );
        });

        returnedLoanItems.forEach(loan -> {
            Book book = loan.getBook();
            if (book == null || book.getGenre() == null) {
                return;
            }

            excludeBookIds.add(book.getId());
            addGenreSignal(genreScores, genreSignals, book.getGenre().getId(), 3.0, SignalType.BORROW_HISTORY);
            profile.registerBook(book, 3.0, SignalType.BORROW_HISTORY, RecommendationFactor.BORROW_HISTORY);
        });
        activeLoanItems.stream()
                .map(BookLoan::getBook)
                .filter(book -> book != null && book.getId() != null)
                .forEach(book -> excludeBookIds.add(book.getId()));

        List<Long> topGenreIds = genreScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(MAX_SIGNAL_GENRES)
                .map(Map.Entry::getKey)
                .toList();

        long returnedCount = bookLoanRepository.countByUserIdAndStatus(currentUser.getId(), BookLoanStatus.RETURNED);
        long wishlistCount = wishlistRepository.countByUserId(currentUser.getId());
        long reviewCount = bookReviewRepository.countByUserIdAndIsActiveTrue(currentUser.getId());
        int activeSignalSourceCount = (interestCount > 0 ? 1 : 0)
                + (returnedCount > 0 ? 1 : 0)
                + (wishlistCount > 0 ? 1 : 0)
                + (reviewCount > 0 ? 1 : 0);
        boolean singleSignalMode = activeSignalSourceCount == 1;
        Set<Long> strictScopedGenreIds = singleSignalMode
                ? resolveStrictScopedGenreIds(currentUser, wishlistItems, reviewItems, returnedLoanItems)
                : Set.of();
        String factorFingerprint = buildFactorFingerprint(
                currentUser,
                wishlistItems,
                reviewItems,
                returnedLoanItems,
                activeLoanItems
        );
        boolean bypassCache = nonce != null;

        CachedRecommendationSnapshot cachedSnapshot = recommendationCache.get(currentUser.getId());
        if (!bypassCache
                && cachedSnapshot != null
                && cachedSnapshot.isFresh()
                && cachedSnapshot.getFactorFingerprint().equals(factorFingerprint)) {
            return trimCachedResponse(cachedSnapshot.getResponse(), safeLimit);
        }

        boolean hasMeaningfulSignals = !topGenreIds.isEmpty()
                && (returnedCount > 0 || wishlistCount > 0 || reviewCount > 0 || interestCount > 0);

        List<Book> candidateBooks = hasMeaningfulSignals
                ? getCandidateBooks(topGenreIds, excludeBookIds, strictScopedGenreIds, singleSignalMode, profile, genreScores)
                : List.of();

        List<RecommendedBookDTO> recommendedBooks = hasMeaningfulSignals
                ? generateAiRecommendations(
                        candidateBooks,
                        genreScores,
                        genreSignals,
                        profile,
                        safeLimit,
                        returnedCount,
                        wishlistCount,
                        reviewCount,
                        requestNonce
                )
                : List.of();

        String strategy = hasMeaningfulSignals
                ? (returnedCount > 0 || wishlistCount > 0 || reviewCount > 0
                    ? "personalized-history-wishlist-review-interest-genre"
                    : "strict-single-signal-personalization")
                : "insufficient-personalization-data";

        PersonalizedRecommendationsResponse response = new PersonalizedRecommendationsResponse(
                strategy,
                interestCount,
                (int) returnedCount,
                (int) wishlistCount,
                (int) reviewCount,
                recommendedBooks
        );
        if (!bypassCache) {
            recommendationCache.put(
                    currentUser.getId(),
                    new CachedRecommendationSnapshot(factorFingerprint, response, Instant.now())
            );
        }
        return trimCachedResponse(response, safeLimit);
    }

    private String buildFactorFingerprint(
            User currentUser,
            List<Wishlist> wishlistItems,
            List<BookReview> reviewItems,
            List<BookLoan> returnedLoanItems,
            List<BookLoan> activeLoanItems
    ) {
        String interestPart = currentUser.getInterestGenres() == null
                ? ""
                : currentUser.getInterestGenres().stream()
                .map(genre -> genre.getId())
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        String wishlistPart = wishlistItems.stream()
                .sorted(Comparator.comparing(Wishlist::getId))
                .map(item -> {
                    Book book = item.getBook();
                    Long bookId = book != null ? book.getId() : null;
                    String notes = item.getNotes() == null ? "" : normalizeText(item.getNotes());
                    String addedAt = item.getAddedAt() == null ? "" : item.getAddedAt().toString();
                    return String.valueOf(bookId) + ":" + notes + ":" + addedAt;
                })
                .collect(Collectors.joining(","));

        String reviewPart = reviewItems.stream()
                .filter(review -> review.getBook() != null)
                .sorted(Comparator
                        .comparing((BookReview review) -> review.getBook().getId())
                        .thenComparing(BookReview::getId))
                .map(review -> review.getBook().getId()
                        + ":" + review.getRating()
                        + ":" + normalizeText(review.getTitle())
                        + ":" + normalizeText(review.getReviewText())
                        + ":" + (review.getUpdatedAt() == null ? "" : review.getUpdatedAt().toString()))
                .collect(Collectors.joining(","));

        String borrowPart = returnedLoanItems.stream()
                .map(BookLoan::getBook)
                .filter(book -> book != null)
                .map(Book::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        String activeLoanPart = activeLoanItems.stream()
                .map(BookLoan::getBook)
                .filter(book -> book != null)
                .map(Book::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return String.join("|", interestPart, wishlistPart, reviewPart, borrowPart, activeLoanPart);
    }

    private PersonalizedRecommendationsResponse trimCachedResponse(
            PersonalizedRecommendationsResponse response,
            int safeLimit
    ) {
        List<RecommendedBookDTO> items = response.getItems() == null
                ? List.of()
                : response.getItems().stream().limit(safeLimit).toList();

        return new PersonalizedRecommendationsResponse(
                response.getStrategy(),
                response.getInterestCount(),
                response.getBorrowHistoryCount(),
                response.getWishlistCount(),
                response.getReviewCount(),
                items
        );
    }

    private List<Book> getCandidateBooks(
            List<Long> topGenreIds,
            Set<Long> excludeBookIds,
            Set<Long> strictScopedGenreIds,
            boolean singleSignalMode,
            PreferenceProfile profile,
            Map<Long, Double> genreScores
    ) {
        List<Long> candidateGenreIds = singleSignalMode && !strictScopedGenreIds.isEmpty()
                ? strictScopedGenreIds.stream().toList()
                : topGenreIds;

        List<Book> candidates = !excludeBookIds.isEmpty()
                ? bookRepository
                .findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInAndIdNotInOrderByAvailableCopiesDescCreatedAtDesc(
                        0,
                        candidateGenreIds,
                        List.copyOf(excludeBookIds),
                        PageRequest.of(0, AI_CANDIDATE_POOL_SIZE)
                )
                : bookRepository
                .findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInOrderByAvailableCopiesDescCreatedAtDesc(
                        0,
                        candidateGenreIds,
                        PageRequest.of(0, AI_CANDIDATE_POOL_SIZE)
                );
        List<Book> balancedGenreCandidates = collectGenreBalancedCandidates(candidateGenreIds, excludeBookIds);

        List<Book> sameGenreFallback = bookRepository
                .findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInOrderByAvailableCopiesDescCreatedAtDesc(
                        0,
                        candidateGenreIds,
                        PageRequest.of(0, AI_CANDIDATE_POOL_SIZE)
                );
        List<Book> broaderFallback = singleSignalMode
                ? List.of()
                : bookRepository.findByActiveTrueAndAvailableCopiesGreaterThanOrderByAvailableCopiesDescCreatedAtDesc(
                        0,
                        PageRequest.of(0, BROAD_CANDIDATE_POOL_SIZE)
                ).stream()
                .filter(book -> !excludeBookIds.contains(book.getId()))
                .toList();
        Set<Long> scopedSeenIds = new LinkedHashSet<>();
        List<Book> scopedPool = new ArrayList<>();
        appendBooks(scopedPool, scopedSeenIds, balancedGenreCandidates, excludeBookIds, Integer.MAX_VALUE);
        appendBooks(scopedPool, scopedSeenIds, candidates, excludeBookIds, Integer.MAX_VALUE);
        appendBooks(scopedPool, scopedSeenIds, sameGenreFallback, excludeBookIds, Integer.MAX_VALUE);
        appendBooks(scopedPool, scopedSeenIds, broaderFallback, excludeBookIds, Integer.MAX_VALUE);

        List<Book> multiFactorMatches = scopedPool.stream()
                .filter(profile::matchesMultipleFactors)
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> wishlistMatches = scopedPool.stream()
                .filter(book -> profile.matchesFactor(book, RecommendationFactor.WISHLIST))
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> reviewMatches = scopedPool.stream()
                .filter(book -> profile.matchesFactor(book, RecommendationFactor.REVIEW))
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> borrowMatches = scopedPool.stream()
                .filter(book -> profile.matchesFactor(book, RecommendationFactor.BORROW_HISTORY))
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> interestMatches = scopedPool.stream()
                .filter(book -> profile.matchesFactor(book, RecommendationFactor.INTEREST))
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> topGenreMatches = scopedPool.stream()
                .filter(book -> book.getGenre() != null && candidateGenreIds.contains(book.getGenre().getId()))
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();
        List<Book> balancedPool = scopedPool.stream()
                .sorted(candidatePriorityComparator(profile, genreScores))
                .toList();

        Set<Long> curatedSeenIds = new LinkedHashSet<>();
        List<Book> curated = new ArrayList<>();
        appendBooks(curated, curatedSeenIds, multiFactorMatches, excludeBookIds, 14);
        appendBooks(curated, curatedSeenIds, wishlistMatches, excludeBookIds, 8);
        appendBooks(curated, curatedSeenIds, reviewMatches, excludeBookIds, 8);
        appendBooks(curated, curatedSeenIds, borrowMatches, excludeBookIds, 8);
        appendBooks(curated, curatedSeenIds, interestMatches, excludeBookIds, 8);
        appendBooks(curated, curatedSeenIds, topGenreMatches, excludeBookIds, 10);
        appendBooks(curated, curatedSeenIds, balancedPool, excludeBookIds, CURATED_CANDIDATE_TARGET);

        return curated;
    }

    private List<Book> collectGenreBalancedCandidates(List<Long> candidateGenreIds, Set<Long> excludeBookIds) {
        if (candidateGenreIds == null || candidateGenreIds.isEmpty()) {
            return List.of();
        }

        Set<Long> seenIds = new LinkedHashSet<>();
        List<Book> balancedCandidates = new ArrayList<>();
        for (Long genreId : candidateGenreIds) {
            if (genreId == null) {
                continue;
            }

            List<Book> perGenreBooks = excludeBookIds.isEmpty()
                    ? bookRepository.findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInOrderByAvailableCopiesDescCreatedAtDesc(
                    0,
                    List.of(genreId),
                    PageRequest.of(0, PER_GENRE_CANDIDATE_LIMIT)
            )
                    : bookRepository.findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInAndIdNotInOrderByAvailableCopiesDescCreatedAtDesc(
                    0,
                    List.of(genreId),
                    List.copyOf(excludeBookIds),
                    PageRequest.of(0, PER_GENRE_CANDIDATE_LIMIT)
            );
            appendBooks(balancedCandidates, seenIds, perGenreBooks, excludeBookIds, Integer.MAX_VALUE);
        }
        return balancedCandidates;
    }

    private void appendBooks(
            List<Book> merged,
            Set<Long> seenIds,
            List<Book> books,
            Set<Long> excludeBookIds,
            int maxToAppend
    ) {
        int appended = 0;
        for (Book book : books) {
            if (appended >= maxToAppend) {
                break;
            }
            if (book == null || book.getId() == null || excludeBookIds.contains(book.getId())) {
                continue;
            }
            if (seenIds.add(book.getId())) {
                merged.add(book);
                appended++;
            }
        }
    }

    private Comparator<Book> candidatePriorityComparator(
            PreferenceProfile profile,
            Map<Long, Double> genreScores
    ) {
        return Comparator
                .comparing((Book book) -> previewRecommendationStrength(book, profile, genreScores))
                .reversed()
                .thenComparing(book -> profile.matchedFactorCount(book), Comparator.reverseOrder())
                .thenComparing(book -> book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(), Comparator.reverseOrder())
                .thenComparing(Book::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private double previewRecommendationStrength(
            Book book,
            PreferenceProfile profile,
            Map<Long, Double> genreScores
    ) {
        if (book == null) {
            return 0.0;
        }
        Long genreId = book.getGenre() != null ? book.getGenre().getId() : null;
        double genreComponent = genreId == null
                ? 0.0
                : Math.min(Math.max(genreScores.getOrDefault(genreId, 0.0), -2.0), 4.5) * 0.45;
        double metadataComponent = Math.min(profile.scoreBook(book), 4.5) * 0.75;
        double factorCoverageComponent = profile.factorCoverageScore(book);
        double availabilityComponent = Math.min(book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(), 4) * 0.2;
        return genreComponent + metadataComponent + factorCoverageComponent + availabilityComponent;
    }

    private Set<Long> resolveInterestScopedGenreIds(Set<Genre> interestGenres) {
        if (interestGenres == null || interestGenres.isEmpty()) {
            return Set.of();
        }
        List<Long> rootGenreIds = interestGenres.stream()
                .map(Genre::getId)
                .filter(id -> id != null)
                .toList();
        return expandGenreScope(rootGenreIds);
    }

    private Set<Long> resolveStrictScopedGenreIds(
            User currentUser,
            List<Wishlist> wishlistItems,
            List<BookReview> reviewItems,
            List<BookLoan> returnedLoanItems
    ) {
        if (currentUser.getInterestGenres() != null && !currentUser.getInterestGenres().isEmpty()) {
            return resolveInterestScopedGenreIds(currentUser.getInterestGenres());
        }

        Set<Long> rootGenreIds = new LinkedHashSet<>();
        wishlistItems.stream()
                .map(Wishlist::getBook)
                .filter(book -> book != null && book.getGenre() != null && book.getGenre().getId() != null)
                .map(book -> book.getGenre().getId())
                .forEach(rootGenreIds::add);
        reviewItems.stream()
                .map(BookReview::getBook)
                .filter(book -> book != null && book.getGenre() != null && book.getGenre().getId() != null)
                .map(book -> book.getGenre().getId())
                .forEach(rootGenreIds::add);
        returnedLoanItems.stream()
                .map(BookLoan::getBook)
                .filter(book -> book != null && book.getGenre() != null && book.getGenre().getId() != null)
                .map(book -> book.getGenre().getId())
                .forEach(rootGenreIds::add);

        return expandGenreScope(rootGenreIds);
    }

    private Set<Long> expandGenreScope(java.util.Collection<Long> rootGenreIds) {
        if (rootGenreIds == null || rootGenreIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> resolved = new LinkedHashSet<>();
        List<Long> frontier = rootGenreIds.stream()
                .filter(id -> id != null)
                .toList();

        frontier.forEach(resolved::add);
        List<Long> currentLevel = frontier;
        while (!currentLevel.isEmpty()) {
            List<Long> nextLevel = new ArrayList<>();
            for (Long genreId : currentLevel) {
                List<Genre> children = genreRepository.findByParentGenreIdAndActiveTrueOrderByDisplayOrderAsc(genreId);
                for (Genre child : children) {
                    if (child.getId() != null && resolved.add(child.getId())) {
                        nextLevel.add(child.getId());
                    }
                }
            }
            currentLevel = nextLevel;
        }
        return resolved;
    }

    private List<RecommendedBookDTO> rankBooks(
            List<Book> candidates,
            Map<Long, Double> genreScores,
            Map<Long, GenreSignal> genreSignals,
            PreferenceProfile profile,
            int limit
    ) {
        List<RecommendedBookDTO> ranked = candidates.stream()
                .map(book -> toRecommendation(book, genreScores, genreSignals, profile))
                .sorted(Comparator.comparing(RecommendedBookDTO::getScore).reversed())
                .toList();
        return diversifyRecommendations(ranked, limit);
    }

    private List<RecommendedBookDTO> generateAiRecommendations(
            List<Book> candidates,
            Map<Long, Double> genreScores,
            Map<Long, GenreSignal> genreSignals,
            PreferenceProfile profile,
            int limit,
            long returnedCount,
            long wishlistCount,
            long reviewCount,
            long requestNonce
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        try {
            List<Book> rankedCandidates = candidates.stream()
                    .sorted(candidatePriorityComparator(profile, genreScores))
                    .toList();
            List<Book> aiCandidateWindow = buildAiCandidateWindow(rankedCandidates, genreScores);

            String candidatePayload = aiCandidateWindow.stream()
                    .map(book -> toCandidatePromptLine(book, genreScores, genreSignals, profile))
                    .collect(Collectors.joining("\n"));

            String topGenreSummary = genreScores.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(MAX_SIGNAL_GENRES)
                    .map(entry -> "genreId=" + entry.getKey() + ", score=" + String.format("%.2f", entry.getValue()))
                    .collect(Collectors.joining("; "));

            String aiResponse = CompletableFuture.supplyAsync(() ->
                    recommendationChatClient.prompt()
                            .system("""
                                    You are a library recommendation engine.
                                    Use the provided user signals and candidate books to choose the best personalized recommendations.
                                    Return strict JSON only.
                                    Response format:
                                    {"items":[{"bookId":123,"score":9.4,"reason":"Short personalized reason"}]}
                                    Rules:
                                    - Recommend at most the requested limit.
                                    - Choose only from the provided candidate books.
                                    - Personalize reasons using borrow history, wishlist, reviews, and genres.
                                    - Do not invent book ids or fields.
                                    - Do not wrap JSON in markdown.
                                    """)
                            .user("""
                                    Requested limit: %d
                                    Refresh nonce: %d
                                    User signals:
                                    - Returned books count: %d
                                    - Wishlist count: %d
                                    - Review count: %d
                                    - Top genre scores: %s

                                    Candidate books:
                                    %s
                                    """.formatted(
                                    limit,
                                    requestNonce,
                                    returnedCount,
                                    wishlistCount,
                                    reviewCount,
                                    topGenreSummary.isBlank() ? "none" : topGenreSummary,
                                    candidatePayload
                            ))
                            .call()
                            .content()
            ).orTimeout(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();

            if (aiResponse == null || aiResponse.isBlank()) {
                return rankBooks(rankedCandidates, genreScores, genreSignals, profile, limit);
            }

            AiRecommendationResponse parsedResponse = objectMapper.readValue(aiResponse, AiRecommendationResponse.class);
            if (parsedResponse == null || parsedResponse.getItems() == null || parsedResponse.getItems().isEmpty()) {
                return rankBooks(rankedCandidates, genreScores, genreSignals, profile, limit);
            }

            Map<Long, Book> candidateById = aiCandidateWindow.stream()
                    .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));

            List<RecommendedBookDTO> aiRecommendations = parsedResponse.getItems().stream()
                    .map(item -> {
                        Book book = candidateById.get(item.getBookId());
                        if (book == null) {
                            return null;
                        }
                        BookDTO bookDTO = bookMapper.toDTO(book);
                        double fallbackScore = toRecommendation(book, genreScores, genreSignals, profile).getScore();
                        double safeScore = item.getScore() == null ? fallbackScore : Math.max(item.getScore(), fallbackScore);
                        String safeReason = item.getReason() == null || item.getReason().isBlank()
                                ? buildReason(
                                genreSignals.get(book.getGenre() != null ? book.getGenre().getId() : null),
                                bookReviewRepository.getAverageRatingByBookId(book.getId()),
                                profile.describeFactorMatches(book)
                        )
                                : item.getReason().trim();
                        return new RecommendedBookDTO(bookDTO, safeScore, capitalize(safeReason));
                    })
                    .filter(item -> item != null)
                    .toList();

            if (aiRecommendations.isEmpty()) {
                return rankBooks(rankedCandidates, genreScores, genreSignals, profile, limit);
            }

            List<RecommendedBookDTO> fallbackRecommendations = rankBooks(
                    candidates,
                    genreScores,
                    genreSignals,
                    profile,
                    Math.max(limit + 4, AI_SELECTION_WINDOW_SIZE)
            );
            return mergeRecommendations(aiRecommendations, fallbackRecommendations, limit);
        } catch (Exception ex) {
            return rankBooks(candidates, genreScores, genreSignals, profile, limit);
        }
    }

    private List<Book> buildAiCandidateWindow(List<Book> rankedCandidates, Map<Long, Double> genreScores) {
        if (rankedCandidates == null || rankedCandidates.isEmpty()) {
            return List.of();
        }

        Map<Long, List<Book>> booksByGenre = rankedCandidates.stream()
                .filter(book -> book.getGenre() != null && book.getGenre().getId() != null)
                .collect(Collectors.groupingBy(
                        book -> book.getGenre().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Long> orderedGenreIds = genreScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(booksByGenre::containsKey)
                .toList();

        Set<Long> seenIds = new LinkedHashSet<>();
        List<Book> aiWindow = new ArrayList<>();
        int round = 0;
        while (aiWindow.size() < AI_SELECTION_WINDOW_SIZE && !orderedGenreIds.isEmpty()) {
            boolean appendedInRound = false;
            for (Long genreId : orderedGenreIds) {
                List<Book> genreBooks = booksByGenre.getOrDefault(genreId, List.of());
                if (round < genreBooks.size()) {
                    Book candidate = genreBooks.get(round);
                    if (candidate != null && candidate.getId() != null && seenIds.add(candidate.getId())) {
                        aiWindow.add(candidate);
                        appendedInRound = true;
                        if (aiWindow.size() >= AI_SELECTION_WINDOW_SIZE) {
                            break;
                        }
                    }
                }
            }
            if (!appendedInRound) {
                break;
            }
            round++;
        }

        if (aiWindow.size() < AI_SELECTION_WINDOW_SIZE) {
            appendBooks(aiWindow, seenIds, rankedCandidates, Set.of(), AI_SELECTION_WINDOW_SIZE - aiWindow.size());
        }
        return aiWindow;
    }

    private RecommendedBookDTO toRecommendation(
            Book book,
            Map<Long, Double> genreScores,
            Map<Long, GenreSignal> genreSignals,
            PreferenceProfile profile
    ) {
        BookDTO bookDTO = bookMapper.toDTO(book);
        Long genreId = book.getGenre() != null ? book.getGenre().getId() : null;
        Double averageRating = bookReviewRepository.getAverageRatingByBookId(book.getId());
        RecommendationScoreBreakdown scoreBreakdown = calculateRecommendationScore(
                book,
                genreScores,
                profile,
                averageRating
        );

        return new RecommendedBookDTO(
                bookDTO,
                scoreBreakdown.getTotalScore(),
                buildReason(genreSignals.get(genreId), averageRating, profile.describeFactorMatches(book))
        );
    }

    private RecommendationScoreBreakdown calculateRecommendationScore(
            Book book,
            Map<Long, Double> genreScores,
            PreferenceProfile profile,
            Double averageRating
    ) {
        Map<RecommendationFactor, Double> factorScores = profile.factorScores(book);
        double interestContribution = Math.min(factorScores.getOrDefault(RecommendationFactor.INTEREST, 0.0), 3.2) * 1.05;
        double wishlistContribution = Math.min(factorScores.getOrDefault(RecommendationFactor.WISHLIST, 0.0), 3.5) * 1.15;
        double reviewContribution = Math.max(
                Math.min(factorScores.getOrDefault(RecommendationFactor.REVIEW, 0.0), 4.0),
                -2.5
        ) * 1.2;
        double borrowContribution = Math.min(factorScores.getOrDefault(RecommendationFactor.BORROW_HISTORY, 0.0), 3.4) * 1.05;

        Long genreId = book.getGenre() != null ? book.getGenre().getId() : null;
        double genreContribution = genreId == null
                ? 0.0
                : Math.min(Math.max(genreScores.getOrDefault(genreId, 0.0), -2.0), 4.5) * 0.55;
        double metadataContribution = Math.min(profile.scoreBook(book), 4.8) * 0.78;
        long matchedFactors = profile.matchedFactorCount(book);
        double factorCoverageBoost = matchedFactors >= 2
                ? 1.6 + ((matchedFactors - 2) * 0.45)
                : 0.0;
        double availabilityBoost = Math.min(book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(), 5) * 0.2;
        double ratingBoost = averageRating == null ? 0.0 : Math.min(averageRating, 5.0) * 0.52;
        double recencyBoost = isRecentlyPublished(book) ? 0.35 : 0.0;
        double totalScore = interestContribution
                + wishlistContribution
                + reviewContribution
                + borrowContribution
                + genreContribution
                + metadataContribution
                + factorCoverageBoost
                + availabilityBoost
                + ratingBoost
                + recencyBoost;

        return new RecommendationScoreBreakdown(
                Math.round(totalScore * 100.0) / 100.0,
                matchedFactors
        );
    }

    private String toCandidatePromptLine(
            Book book,
            Map<Long, Double> genreScores,
            Map<Long, GenreSignal> genreSignals,
            PreferenceProfile profile
    ) {
        Long genreId = book.getGenre() != null ? book.getGenre().getId() : null;
        Double averageRating = bookReviewRepository.getAverageRatingByBookId(book.getId());
        GenreSignal signal = genreSignals.get(genreId);
        List<String> matchedFactors = profile.describeFactorMatches(book);
        String factorBreakdown = profile.factorScores(book).entrySet().stream()
                .filter(entry -> Math.abs(entry.getValue()) >= 0.4)
                .sorted(Map.Entry.<RecommendationFactor, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT) + "=" + String.format("%.2f", entry.getValue()))
                .collect(Collectors.joining(", "));
        return """
                - bookId=%d, title=%s, author=%s, genre=%s, genreScore=%s, availableCopies=%d, averageRating=%s, supportSignals=%s, matchedFactors=%s, factorBreakdown=%s
                """.formatted(
                book.getId(),
                safePromptText(book.getTitle()),
                safePromptText(book.getAuthor()),
                safePromptText(book.getGenre() != null ? book.getGenre().getName() : "Unknown"),
                genreId == null ? "0.00" : String.format("%.2f", genreScores.getOrDefault(genreId, 0.0)),
                book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(),
                averageRating == null ? "N/A" : String.format("%.1f", averageRating),
                summarizeSignal(signal),
                matchedFactors.isEmpty() ? "none" : String.join(", ", matchedFactors),
                factorBreakdown.isBlank() ? "none" : factorBreakdown
        );
    }

    private String summarizeSignal(GenreSignal signal) {
        if (signal == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (signal.getBorrowHistoryCount() > 0) {
            parts.add(signal.getBorrowHistoryCount() + " returned");
        }
        if (signal.getInterestCount() > 0) {
            parts.add(signal.getInterestCount() + " selected interests");
        }
        if (signal.getWishlistCount() > 0) {
            parts.add(signal.getWishlistCount() + " wishlist");
        }
        if (signal.getReviewCount() > 0) {
            parts.add(signal.getReviewCount() + " reviews");
        }
        if (signal.getPositiveReviewCount() > 0) {
            parts.add(signal.getPositiveReviewCount() + " positive reviews");
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private String safePromptText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\n", " ").replace("\r", " ").trim();
    }

    private String buildReason(GenreSignal signal, Double averageRating, List<String> factorMatches) {
        List<String> reasons = new ArrayList<>();
        if (factorMatches != null) {
            reasons.addAll(factorMatches);
        }
        if (signal != null) {
            if (signal.getInterestCount() > 0 && !reasons.contains("matches your selected interests")) {
                reasons.add("matches your selected interests");
            }
            if (signal.getWishlistCount() > 0 && !reasons.contains("aligns with books you saved in your wishlist")) {
                reasons.add("matches books in your wishlist");
            }
            if (signal.getPositiveReviewCount() > 0 && !reasons.contains("reflects the genres and themes you rated highly")) {
                reasons.add("fits genres you rated highly");
            }
            if (signal.getBorrowHistoryCount() > 0 && !reasons.contains("connects with your past borrowing history")) {
                reasons.add("aligns with your reading history");
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("matches your strongest reading genre");
        }
        if (averageRating != null && averageRating >= 4.0) {
            reasons.add(String.format("has a strong %.1f reader rating", averageRating));
        }

        return capitalize(String.join(" and ", reasons));
    }

    private boolean isRecentlyPublished(Book book) {
        return book.getPublicationDate() != null && book.getPublicationDate().isAfter(LocalDate.now().minusYears(5));
    }

    private List<RecommendedBookDTO> mergeRecommendations(
            List<RecommendedBookDTO> primary,
            List<RecommendedBookDTO> fallback,
            int limit
    ) {
        Map<Long, RecommendedBookDTO> merged = new LinkedHashMap<>();
        for (RecommendedBookDTO item : primary) {
            Long bookId = item != null && item.getBook() != null ? item.getBook().getId() : null;
            if (bookId != null) {
                merged.putIfAbsent(bookId, item);
            }
        }
        for (RecommendedBookDTO item : fallback) {
            Long bookId = item != null && item.getBook() != null ? item.getBook().getId() : null;
            if (bookId != null) {
                merged.putIfAbsent(bookId, item);
            }
        }
        return diversifyRecommendations(new ArrayList<>(merged.values()), limit);
    }

    private List<RecommendedBookDTO> diversifyRecommendations(List<RecommendedBookDTO> ranked, int limit) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<RecommendedBookDTO> remaining = new ArrayList<>(ranked);
        List<RecommendedBookDTO> diversified = new ArrayList<>();
        Map<Long, Integer> genreUsage = new HashMap<>();
        Map<String, Integer> authorUsage = new HashMap<>();

        while (!remaining.isEmpty() && diversified.size() < limit) {
            RecommendedBookDTO next = remaining.stream()
                    .max(Comparator.comparing(item -> adjustedDiversifiedScore(item, genreUsage, authorUsage)))
                    .orElse(null);
            if (next == null) {
                break;
            }
            diversified.add(next);
            remaining.remove(next);
            if (next.getBook() != null) {
                Long genreId = next.getBook().getGenreId();
                if (genreId != null) {
                    genreUsage.merge(genreId, 1, Integer::sum);
                }
                String author = normalizeText(next.getBook().getAuthor());
                if (!author.isBlank()) {
                    authorUsage.merge(author, 1, Integer::sum);
                }
            }
        }
        return diversified;
    }

    private double adjustedDiversifiedScore(
            RecommendedBookDTO item,
            Map<Long, Integer> genreUsage,
            Map<String, Integer> authorUsage
    ) {
        double score = item.getScore() == null ? 0.0 : item.getScore();
        if (item.getBook() == null) {
            return score;
        }
        Long genreId = item.getBook().getGenreId();
        if (genreId != null) {
            score -= genreUsage.getOrDefault(genreId, 0) * 0.9;
        }
        String author = normalizeText(item.getBook().getAuthor());
        if (!author.isBlank()) {
            score -= authorUsage.getOrDefault(author, 0) * 0.6;
        }
        return score;
    }

    private void addGenreSignal(
            Map<Long, Double> genreScores,
            Map<Long, GenreSignal> genreSignals,
            Long genreId,
            double score,
            SignalType signalType
    ) {
        genreScores.merge(genreId, score, Double::sum);
        GenreSignal signal = genreSignals.computeIfAbsent(genreId, ignored -> new GenreSignal());
        signal.increment(signalType);
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private enum SignalType {
        INTEREST,
        WISHLIST,
        REVIEW,
        POSITIVE_REVIEW,
        BORROW_HISTORY
    }

    private enum RecommendationFactor {
        INTEREST,
        WISHLIST,
        REVIEW,
        BORROW_HISTORY
    }

    @Data
    @AllArgsConstructor
    private static class GenreSignal {
        private int interestCount;
        private int wishlistCount;
        private int reviewCount;
        private int positiveReviewCount;
        private int borrowHistoryCount;

        private GenreSignal() {
            this(0, 0, 0, 0, 0);
        }

        private void increment(SignalType signalType) {
            switch (signalType) {
                case INTEREST -> interestCount++;
                case WISHLIST -> wishlistCount++;
                case REVIEW -> reviewCount++;
                case POSITIVE_REVIEW -> {
                    reviewCount++;
                    positiveReviewCount++;
                }
                case BORROW_HISTORY -> borrowHistoryCount++;
            }
        }
    }

    @Data
    private static class PreferenceProfile {
        private final Map<String, Double> authorScores = new HashMap<>();
        private final Map<String, Double> languageScores = new HashMap<>();
        private final Map<String, Double> audienceScores = new HashMap<>();
        private final Map<String, Double> tokenScores = new HashMap<>();
        private final Set<String> interestGenreTokens = new HashSet<>();
        private final Map<RecommendationFactor, FactorPreference> factorPreferences = new EnumMap<>(RecommendationFactor.class);

        private void registerInterestGenre(Genre genre) {
            if (genre == null) {
                return;
            }
            factorPreferences.computeIfAbsent(RecommendationFactor.INTEREST, ignored -> new FactorPreference())
                    .registerGenre(genre, 1.4);
            tokenize(genre.getName()).forEach(token -> {
                tokenScores.merge(token, 1.2, Double::sum);
                interestGenreTokens.add(token);
            });
            tokenize(genre.getHierarchyPath()).forEach(token -> {
                tokenScores.merge(token, 0.8, Double::sum);
                interestGenreTokens.add(token);
            });
        }

        private void registerBook(Book book, double weight, SignalType signalType, RecommendationFactor factor) {
            if (book == null) {
                return;
            }
            double normalizedWeight = switch (signalType) {
                case WISHLIST -> Math.max(weight, 0.0) * 0.7;
                case POSITIVE_REVIEW -> Math.max(weight, 0.0) * 0.8;
                case BORROW_HISTORY -> Math.max(weight, 0.0) * 0.6;
                case REVIEW -> weight * 0.55;
                case INTEREST -> Math.max(weight, 0.0) * 0.5;
            };

            factorPreferences.computeIfAbsent(factor, ignored -> new FactorPreference())
                    .registerBook(book, normalizedWeight);

            mergeIfPresent(authorScores, book.getAuthor(), normalizedWeight);
            mergeIfPresent(languageScores, book.getLanguage(), normalizedWeight * 0.35);
            mergeIfPresent(audienceScores, book.getAudienceLevel(), normalizedWeight * 0.4);

            List<String> metadataTokens = new ArrayList<>();
            metadataTokens.addAll(tokenize(book.getTitle()));
            metadataTokens.addAll(tokenize(book.getPublisher()));
            metadataTokens.addAll(tokenize(book.getDescription()));
            metadataTokens.addAll(tokenize(book.getSubjectHeadings()));
            metadataTokens.addAll(tokenize(book.getGenre() != null ? book.getGenre().getName() : null));
            metadataTokens.addAll(tokenize(book.getGenre() != null ? book.getGenre().getHierarchyPath() : null));

            metadataTokens.stream()
                    .limit(TOKEN_LIMIT_PER_BOOK)
                    .forEach(token -> tokenScores.merge(token, normalizedWeight * 0.22, Double::sum));
        }

        private double scoreBook(Book book) {
            if (book == null) {
                return 0.0;
            }
            double score = 0.0;
            score += authorScores.getOrDefault(normalizeText(book.getAuthor()), 0.0) * 0.75;
            score += languageScores.getOrDefault(normalizeText(book.getLanguage()), 0.0);
            score += audienceScores.getOrDefault(normalizeText(book.getAudienceLevel()), 0.0);

            Set<String> candidateTokens = new LinkedHashSet<>();
            candidateTokens.addAll(tokenize(book.getTitle()));
            candidateTokens.addAll(tokenize(book.getDescription()));
            candidateTokens.addAll(tokenize(book.getSubjectHeadings()));
            candidateTokens.addAll(tokenize(book.getPublisher()));
            if (book.getGenre() != null) {
                candidateTokens.addAll(tokenize(book.getGenre().getName()));
                candidateTokens.addAll(tokenize(book.getGenre().getHierarchyPath()));
            }

            double tokenScore = candidateTokens.stream()
                    .limit(TOKEN_LIMIT_PER_BOOK)
                    .mapToDouble(token -> tokenScores.getOrDefault(token, 0.0))
                    .sum();

            long genreTokenMatches = candidateTokens.stream()
                    .filter(interestGenreTokens::contains)
                    .count();

            score += Math.min(tokenScore, 4.0);
            score += Math.min(genreTokenMatches, 3) * 0.35;
            return score;
        }

        private boolean matchesAnyExplicitFactor(Book book) {
            return matchedFactorCount(book) >= 1;
        }

        private boolean matchesMultipleFactors(Book book) {
            return matchedFactorCount(book) >= 2;
        }

        private double factorCoverageScore(Book book) {
            Map<RecommendationFactor, Double> scores = factorScores(book);
            long matchedFactors = scores.values().stream().filter(score -> score >= 0.9).count();
            double boundedSum = scores.values().stream()
                    .filter(score -> score > 0)
                    .mapToDouble(score -> Math.min(score, 3.2))
                    .sum();
            return boundedSum + (matchedFactors >= 2 ? 1.2 + ((matchedFactors - 2) * 0.4) : 0.0);
        }

        private Map<RecommendationFactor, Double> factorScores(Book book) {
            Map<RecommendationFactor, Double> scores = new EnumMap<>(RecommendationFactor.class);
            for (RecommendationFactor factor : RecommendationFactor.values()) {
                FactorPreference preference = factorPreferences.get(factor);
                scores.put(factor, preference == null ? 0.0 : preference.score(book));
            }
            return scores;
        }

        private long matchedFactorCount(Book book) {
            return factorScores(book).values().stream().filter(score -> score >= 0.9).count();
        }

        private List<String> describeFactorMatches(Book book) {
            if (book == null) {
                return List.of();
            }

            return factorScores(book).entrySet().stream()
                    .filter(entry -> entry.getValue() >= 0.9)
                    .sorted(Map.Entry.<RecommendationFactor, Double>comparingByValue().reversed())
                    .map(entry -> switch (entry.getKey()) {
                        case INTEREST -> "matches your selected interests";
                        case WISHLIST -> "aligns with books you saved in your wishlist";
                        case REVIEW -> "reflects the genres and themes you rated highly";
                        case BORROW_HISTORY -> "connects with your past borrowing history";
                    })
                    .distinct()
                    .limit(3)
                    .toList();
        }

        private boolean matchesFactor(Book book, RecommendationFactor factor) {
            FactorPreference preference = factorPreferences.get(factor);
            return preference != null && preference.matches(book);
        }

        private void mergeIfPresent(Map<String, Double> target, String value, double score) {
            String key = normalizeText(value);
            if (!key.isBlank()) {
                target.merge(key, score, Double::sum);
            }
        }
    }

    @Data
    private static class FactorPreference {
        private final Map<Long, Double> genreScores = new HashMap<>();
        private final Map<String, Double> authorScores = new HashMap<>();
        private final Map<String, Double> languageScores = new HashMap<>();
        private final Map<String, Double> audienceScores = new HashMap<>();
        private final Map<String, Double> tokenScores = new HashMap<>();

        private void registerGenre(Genre genre, double weight) {
            if (genre == null) {
                return;
            }
            if (genre.getId() != null) {
                genreScores.merge(genre.getId(), weight, Double::sum);
            }
            tokenize(genre.getName()).forEach(token -> tokenScores.merge(token, weight * 0.45, Double::sum));
            tokenize(genre.getHierarchyPath()).forEach(token -> tokenScores.merge(token, weight * 0.35, Double::sum));
        }

        private void registerBook(Book book, double weight) {
            if (book == null) {
                return;
            }
            if (book.getGenre() != null && book.getGenre().getId() != null) {
                genreScores.merge(book.getGenre().getId(), weight * 0.8, Double::sum);
            }
            mergeIfPresent(authorScores, book.getAuthor(), weight * 0.95);
            mergeIfPresent(languageScores, book.getLanguage(), weight * 0.35);
            mergeIfPresent(audienceScores, book.getAudienceLevel(), weight * 0.4);

            List<String> tokens = new ArrayList<>();
            tokens.addAll(tokenize(book.getTitle()));
            tokens.addAll(tokenize(book.getDescription()));
            tokens.addAll(tokenize(book.getSubjectHeadings()));
            tokens.addAll(tokenize(book.getPublisher()));
            if (book.getGenre() != null) {
                tokens.addAll(tokenize(book.getGenre().getName()));
                tokens.addAll(tokenize(book.getGenre().getHierarchyPath()));
            }
            tokens.stream()
                    .limit(TOKEN_LIMIT_PER_BOOK)
                    .forEach(token -> tokenScores.merge(token, weight * 0.22, Double::sum));
        }

        private boolean matches(Book book) {
            return score(book) >= 0.9;
        }

        private double score(Book book) {
            if (book == null) {
                return 0.0;
            }
            double score = 0.0;
            if (book.getGenre() != null && book.getGenre().getId() != null) {
                score += genreScores.getOrDefault(book.getGenre().getId(), 0.0);
            }
            score += authorScores.getOrDefault(normalizeText(book.getAuthor()), 0.0);
            score += languageScores.getOrDefault(normalizeText(book.getLanguage()), 0.0);
            score += audienceScores.getOrDefault(normalizeText(book.getAudienceLevel()), 0.0);

            Set<String> candidateTokens = new LinkedHashSet<>();
            candidateTokens.addAll(tokenize(book.getTitle()));
            candidateTokens.addAll(tokenize(book.getDescription()));
            candidateTokens.addAll(tokenize(book.getSubjectHeadings()));
            candidateTokens.addAll(tokenize(book.getPublisher()));
            if (book.getGenre() != null) {
                candidateTokens.addAll(tokenize(book.getGenre().getName()));
                candidateTokens.addAll(tokenize(book.getGenre().getHierarchyPath()));
            }

            score += candidateTokens.stream()
                    .limit(TOKEN_LIMIT_PER_BOOK)
                    .mapToDouble(token -> tokenScores.getOrDefault(token, 0.0))
                    .sum();
            return score;
        }

        private void mergeIfPresent(Map<String, Double> target, String value, double score) {
            String key = normalizeText(value);
            if (!key.isBlank()) {
                target.merge(key, score, Double::sum);
            }
        }
    }

    @Data
    @AllArgsConstructor
    private static class RecommendationScoreBreakdown {
        private Double totalScore;
        private long matchedFactors;
    }

    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "your", "into", "about",
            "book", "books", "guide", "introduction", "edition", "volume", "part", "study",
            "reader", "readers", "library", "libraries"
    );

    @Data
    private static class AiRecommendationResponse {
        private List<AiRecommendationItem> items;
    }

    @Data
    private static class AiRecommendationItem {
        private Long bookId;
        private Double score;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    private static class CachedRecommendationSnapshot {
        private String factorFingerprint;
        private PersonalizedRecommendationsResponse response;
        private Instant createdAt;

        private boolean isFresh() {
            return createdAt != null && createdAt.plus(CACHE_TTL).isAfter(Instant.now());
        }
    }
}

