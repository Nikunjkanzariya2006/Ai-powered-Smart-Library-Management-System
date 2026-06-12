package com.nik.service.impl;

import com.nik.exception.BookException;
import com.nik.mapper.BookMapper;
import com.nik.model.Book;
import com.nik.payload.dto.BookDTO;
import com.nik.repository.BookRepository;
import com.nik.service.BookCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookCacheServiceImpl implements BookCacheService {

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    @Override
    @Cacheable(value = "books", key = "#bookId", unless = "#result == null")
    public BookDTO getBookDetailsOnly(Long bookId) throws BookException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));
        return bookMapper.toDTO(book);
    }

    @Override
    @Cacheable(value = "booksByIsbn", key = "#isbn", unless = "#result == null")
    public BookDTO getBookByIsbn(String isbn) throws BookException {
        Book book = bookRepository.findByIsbn(isbn)
                .orElseThrow(() -> new BookException("Book not found with ISBN: " + isbn));
        return bookMapper.toDTO(book);
    }

    @Override
    @CacheEvict(value = "books", key = "#bookId")
    public void evictBookCache(Long bookId) {
        // Spring AOP intercepts this and evicts the entry
    }

    @Override
    @CacheEvict(value = "booksByIsbn", allEntries = true)
    public void evictAllBookIsbnCache() {
        // Spring AOP intercepts this and evicts all entries
    }
}