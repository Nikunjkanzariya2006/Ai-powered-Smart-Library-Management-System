package com.nik.repository;

import com.nik.model.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.List;

/**
 * Repository interface for Book entity.
 * Provides CRUD operations and custom query methods for searching and filtering books.
 */
@Repository
public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Find a book by ISBN
     */
    Optional<Book> findByIsbn(String isbn);

    /**
     * Check if a book exists with the given ISBN
     */
    boolean existsByIsbn(String isbn);


    /**
     * Advanced search with filters - search by title, author, ISBN and filter by genre
     */
    @Query(
        value = """
            SELECT b.*
            FROM books b
            JOIN genres g ON g.id = b.genre_id
            WHERE (
                CASE
                    WHEN COALESCE(:searchTerm, '') = '' THEN TRUE
                    ELSE (
                        b.title ILIKE CONCAT('%', :searchTerm, '%')
                        OR b.author ILIKE CONCAT('%', :searchTerm, '%')
                        OR b.isbn ILIKE CONCAT('%', :searchTerm, '%')
                        OR g.name ILIKE CONCAT('%', :searchTerm, '%')
                        OR g.code ILIKE CONCAT('%', :searchTerm, '%')
                        OR COALESCE(g.hierarchy_path, '') ILIKE CONCAT('%', :searchTerm, '%')
                        OR to_tsvector('simple', COALESCE(b.title, '') || ' ' || COALESCE(b.author, ''))
                            @@ websearch_to_tsquery('simple', :searchTerm)
                    )
                END
            )
            AND (:genreId IS NULL OR b.genre_id = :genreId)
            AND (:availableOnly = false OR b.available_copies > 0)
            AND (:activeOnly = false OR b.active = true)
            """,
        countQuery = """
            SELECT COUNT(*)
            FROM books b
            JOIN genres g ON g.id = b.genre_id
            WHERE (
                CASE
                    WHEN COALESCE(:searchTerm, '') = '' THEN TRUE
                    ELSE (
                        b.title ILIKE CONCAT('%', :searchTerm, '%')
                        OR b.author ILIKE CONCAT('%', :searchTerm, '%')
                        OR b.isbn ILIKE CONCAT('%', :searchTerm, '%')
                        OR g.name ILIKE CONCAT('%', :searchTerm, '%')
                        OR g.code ILIKE CONCAT('%', :searchTerm, '%')
                        OR COALESCE(g.hierarchy_path, '') ILIKE CONCAT('%', :searchTerm, '%')
                        OR to_tsvector('simple', COALESCE(b.title, '') || ' ' || COALESCE(b.author, ''))
                            @@ websearch_to_tsquery('simple', :searchTerm)
                    )
                END
            )
            AND (:genreId IS NULL OR b.genre_id = :genreId)
            AND (:availableOnly = false OR b.available_copies > 0)
            AND (:activeOnly = false OR b.active = true)
            """,
        nativeQuery = true
    )
    Page<Book> searchBooksWithFilters(
        @Param("searchTerm") String searchTerm,
        @Param("genreId") Long genreId,
        @Param("availableOnly") boolean availableOnly,
        @Param("activeOnly") boolean activeOnly,
        Pageable pageable
    );


    /**
     * Count total active books
     */
    long countByActiveTrue();

    /**
     * Count available books
     */
    @Query("SELECT COUNT(b) FROM Book b WHERE b.availableCopies > 0 AND b.active = true")
    long countAvailableBooks();

    @Query("SELECT b.isbn FROM Book b WHERE b.isbn IN :isbns")
    Set<String> findExistingIsbns(@Param("isbns") Set<String> isbns);

    List<Book> findTop8ByActiveTrueAndAvailableCopiesGreaterThanOrderByAvailableCopiesDescCreatedAtDesc(Integer availableCopies);

    List<Book> findTop8ByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInOrderByAvailableCopiesDescCreatedAtDesc(
            Integer availableCopies,
            List<Long> genreIds
    );

    List<Book> findTop8ByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInAndIdNotInOrderByAvailableCopiesDescCreatedAtDesc(
            Integer availableCopies,
            List<Long> genreIds,
            List<Long> excludeIds
    );

    List<Book> findByActiveTrueAndAvailableCopiesGreaterThanOrderByAvailableCopiesDescCreatedAtDesc(
            Integer availableCopies,
            Pageable pageable
    );

    List<Book> findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInOrderByAvailableCopiesDescCreatedAtDesc(
            Integer availableCopies,
            List<Long> genreIds,
            Pageable pageable
    );

    List<Book> findByActiveTrueAndAvailableCopiesGreaterThanAndGenre_IdInAndIdNotInOrderByAvailableCopiesDescCreatedAtDesc(
            Integer availableCopies,
            List<Long> genreIds,
            List<Long> excludeIds,
            Pageable pageable
    );
}

