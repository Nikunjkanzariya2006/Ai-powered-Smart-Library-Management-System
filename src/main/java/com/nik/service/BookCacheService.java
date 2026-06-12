package com.nik.service;

import com.nik.exception.BookException;
import com.nik.payload.dto.BookDTO;

public interface BookCacheService {
    BookDTO getBookDetailsOnly(Long bookId) throws BookException;
    BookDTO getBookByIsbn(String isbn) throws BookException;
    void evictBookCache(Long bookId);
    void evictAllBookIsbnCache();
}