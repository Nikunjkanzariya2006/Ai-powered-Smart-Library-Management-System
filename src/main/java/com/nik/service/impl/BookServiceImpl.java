package com.nik.service.impl;

import com.nik.domain.BookLoanStatus;
import com.nik.exception.BookException;
import com.nik.exception.UserException;
import com.nik.mapper.BookMapper;
import com.nik.model.Book;
import com.nik.model.User;
import com.nik.payload.dto.BookDTO;
import com.nik.payload.request.BookSearchRequest;
import com.nik.payload.response.PageResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.ReservationRepository;
import com.nik.service.BookService;
import com.nik.service.UserService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of BookService interface.
 * Handles all business logic for book catalog operations.
 *
 * SIMPLIFIED VERSION - Uses unified search approach
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final BookLoanRepository bookLoanRepository;
    private final UserService userService;
    private final ReservationRepository reservationRepository;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "title", "author", "isbn", "availableCopies", "totalCopies", "price"
    );
    private static final Map<String, String> SORT_FIELD_TO_COLUMN = Map.of(
            "createdAt", "created_at",
            "updatedAt", "updated_at",
            "title", "title",
            "author", "author",
            "isbn", "isbn",
            "availableCopies", "available_copies",
            "totalCopies", "total_copies",
            "price", "price"
    );

    @Override
    public BookDTO createBook(BookDTO bookDTO) throws BookException {

        if (!StringUtils.hasText(bookDTO.getIsbn())) {
            throw new BookException("ISBN is mandatory");
        }

        // Validate ISBN uniqueness
        if (bookRepository.existsByIsbn(bookDTO.getIsbn())) {
            throw new BookException("Book with ISBN " + bookDTO.getIsbn() + " already exists");
        }

        Book book = bookMapper.toEntity(bookDTO);

        // Validate available copies
        if (!book.isAvailableCopiesValid()) {
            throw new BookException("Available copies cannot exceed total copies");
        }

        Book savedBook = bookRepository.save(book);

        return bookMapper.toDTO(savedBook);
    }

    @Override
    public List<BookDTO> createBooksBulk(List<BookDTO> bookDTOs) throws BookException {
        if (bookDTOs == null || bookDTOs.isEmpty()) {
            throw new BookException("Book list cannot be null or empty");
        }

        Set<String> requestIsbns = new HashSet<>();

        // Validate all books before creating any
        for (BookDTO bookDTO : bookDTOs) {
            if (!StringUtils.hasText(bookDTO.getIsbn())) {
                throw new BookException("ISBN is required for each book in bulk create request");
            }

            if (!requestIsbns.add(bookDTO.getIsbn())) {
                throw new BookException("Duplicate ISBN in request: " + bookDTO.getIsbn());
            }

            // Check if ISBN already exists in database
            if (bookRepository.existsByIsbn(bookDTO.getIsbn())) {
                throw new BookException("Book with ISBN " + bookDTO.getIsbn() + " already exists");
            }

            // Validate available copies
            if (bookDTO.getAvailableCopies() > bookDTO.getTotalCopies()) {
                throw new BookException("Available copies cannot exceed total copies for ISBN: " + bookDTO.getIsbn());
            }

            // Validate genre exists (will throw exception if not found)
            if (bookDTO.getGenreId() == null) {
                throw new BookException("Genre ID is required for ISBN: " + bookDTO.getIsbn());
            }
        }

        // All validations passed, now create all books
        List<Book> booksToSave = new ArrayList<>();
        for (BookDTO bookDTO : bookDTOs) {
            Book book = bookMapper.toEntity(bookDTO);
            book.setActive(true); // Ensure new books are active by default
            booksToSave.add(book);
        }

        // Save all books in a single batch
        List<Book> savedBooks = bookRepository.saveAll(booksToSave);

        // Convert to DTOs and return
        return savedBooks.stream()
            .map(bookMapper::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    public BookDTO getBookById(Long bookId) throws BookException, UserException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));
        BookDTO bookDTO = bookMapper.toDTO(book);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        if (isAuthenticated) {
            User currentUser = userService.getCurrentUser();
            boolean alreadyHasLoan = bookLoanRepository
                    .existsByUserIdAndBookIdAndStatus(
                            currentUser.getId(), bookId,
                            BookLoanStatus.CHECKED_OUT);
            boolean alreadyHaveReservation = reservationRepository
                    .findActiveReservationByUserAndBook(currentUser.getId(), bookId).isPresent();

            bookDTO.setAlreadyHaveLoan(alreadyHasLoan);
            bookDTO.setAlreadyHaveReservation(alreadyHaveReservation);
        } else {
            bookDTO.setAlreadyHaveLoan(false);
            bookDTO.setAlreadyHaveReservation(false);
        }

        return bookDTO;
    }

    @Override
    public BookDTO getBookByIsbn(String isbn) throws BookException {
        Book book = bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new BookException("Book not found with ISBN: " + isbn));
        return bookMapper.toDTO(book);
    }

    @Override
    public BookDTO updateBook(Long bookId, BookDTO bookDTO) throws BookException {
        Book existingBook = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        // Validate available copies
        if (bookDTO.getAvailableCopies() > bookDTO.getTotalCopies()) {
            throw new BookException("Available copies cannot exceed total copies");
        }

        // ISBN is immutable after creation. Update payload may omit it.
        if (StringUtils.hasText(bookDTO.getIsbn()) && !existingBook.getIsbn().equals(bookDTO.getIsbn())) {
            throw new BookException("ISBN cannot be changed after book creation");
        }

        // Update the book
        bookMapper.updateEntityFromDTO(bookDTO, existingBook);

        Book updatedBook = bookRepository.save(existingBook);
        return bookMapper.toDTO(updatedBook);
    }

    @Override
    public void deleteBook(Long bookId) throws BookException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        // Soft delete - mark as inactive
        book.setActive(false);
        bookRepository.save(book);
    }

    @Override
    public void hardDeleteBook(Long bookId) throws BookException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        // Hard delete - permanently remove from database
        bookRepository.delete(book);
    }

    @Override
    public PageResponse<BookDTO> searchBooksWithFilters(BookSearchRequest searchRequest) {
        if (searchRequest == null) {
            searchRequest = new BookSearchRequest();
        }

        Pageable pageable = createPageable(
                searchRequest.getPage() == null ? 0 : searchRequest.getPage(),
                searchRequest.getSize() == null ? 20 : searchRequest.getSize(),
                searchRequest.getSortBy(),
                searchRequest.getSortDirection()
        );

        String safeSearchTerm = searchRequest.getSearchTerm();
        if (safeSearchTerm != null) {
            safeSearchTerm = safeSearchTerm.trim();
        }

        Page<Book> bookPage = bookRepository.searchBooksWithFilters(
                safeSearchTerm,
                searchRequest.getGenreId(),
                searchRequest.getAvailableOnly() != null ? searchRequest.getAvailableOnly() : false,
                searchRequest.getActiveOnly() == null || searchRequest.getActiveOnly(),
                pageable
        );

        return convertToPageResponse(bookPage);
    }

    @Override
    public long getTotalActiveBooks() {
        return bookRepository.countByActiveTrue();
    }

    @Override
    public long getTotalAvailableBooks() {
        return bookRepository.countAvailableBooks();
    }

    /**
     * Helper method to create Pageable object with sorting
     */
    private Pageable createPageable(int page, int size, String sortBy, String sortDirection) {
        page = Math.max(page, 0);
        // Validate and limit page size
        size = Math.min(size, 100); // Maximum 100 items per page
        size = Math.max(size, 1);   // Minimum 1 item per page

        String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        String databaseSortBy = SORT_FIELD_TO_COLUMN.getOrDefault(safeSortBy, "created_at");
        String safeSortDirection = sortDirection == null ? "DESC" : sortDirection;
        Sort sort = safeSortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(databaseSortBy).ascending()
                : Sort.by(databaseSortBy).descending();

        return PageRequest.of(page, size, sort);
    }

    /**
     * Helper method to convert Page<Book> to PageResponse<BookDTO>
     */
    private PageResponse<BookDTO> convertToPageResponse(Page<Book> bookPage) {
        List<BookDTO> bookDTOs = bookPage.getContent()
                .stream()
                .map(bookMapper::toDTO)
                .collect(Collectors.toList());

        return new PageResponse<>(
                bookDTOs,
                bookPage.getNumber(),
                bookPage.getSize(),
                bookPage.getTotalElements(),
                bookPage.getTotalPages(),
                bookPage.isLast(),
                bookPage.isFirst(),
                bookPage.isEmpty()
        );
    }
}


