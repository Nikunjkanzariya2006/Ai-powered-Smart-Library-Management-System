package com.nik.service.impl;

import com.nik.exception.BookException;
import com.nik.exception.UserException;
import com.nik.exception.WishlistException;
import com.nik.mapper.WishlistMapper;
import com.nik.model.Book;
import com.nik.model.User;
import com.nik.model.Wishlist;
import com.nik.payload.dto.WishlistDTO;
import com.nik.payload.response.PageResponse;
import com.nik.repository.BookRepository;
import com.nik.repository.WishlistRepository;
import com.nik.service.UserService;
import com.nik.service.WishlistService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of WishlistService interface.
 * Handles all business logic for user wishlists.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WishlistServiceImpl implements WishlistService {

    private final WishlistRepository wishlistRepository;
    private final BookRepository bookRepository;
    private final UserService userService ;
    private final WishlistMapper wishlistMapper;
    @Override
    public WishlistDTO addToWishlist(Long bookId, String notes) throws BookException, WishlistException, UserException {
        User currentUser = userService.getCurrentUser();
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        if (!book.getActive()) {
            throw new BookException("Cannot add inactive book to wishlist");
        }

        if (wishlistRepository.existsByUserIdAndBookId(currentUser.getId(), bookId)) {
            throw new WishlistException("Book is already in your wishlist");
        }

        Wishlist wishlist = new Wishlist();
        wishlist.setUser(currentUser);
        wishlist.setBook(book);
        wishlist.setNotes(notes);

        Wishlist savedWishlist = wishlistRepository.save(wishlist);

        return wishlistMapper.toDTO(savedWishlist);
    }

    @Override
    public void removeFromWishlist(Long bookId) throws WishlistException, UserException {
        User currentUser = userService.getCurrentUser();
        if (!wishlistRepository.existsByUserIdAndBookId(currentUser.getId(), bookId)) {
            throw new WishlistException("Book is not in your wishlist");
        }

        wishlistRepository.deleteByUserIdAndBookId(currentUser.getId(), bookId);
    }

    @Override
    public PageResponse<WishlistDTO> getMyWishlist(int page, int size) throws UserException {
        User currentUser = userService.getCurrentUser();
        return getUserWishlist(currentUser.getId(), page, size);
    }

    @Override
    public PageResponse<WishlistDTO> getUserWishlist(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("addedAt").descending());
        Page<Wishlist> wishlistPage = wishlistRepository.findByUserId(userId, pageable);
        return convertToPageResponse(wishlistPage);
    }

    @Override
    public boolean isBookInWishlist(Long bookId) throws UserException {
        User currentUser = userService.getCurrentUser();
        return wishlistRepository.existsByUserIdAndBookId(currentUser.getId(), bookId);
    }

    @Override
    public WishlistDTO updateWishlistNotes(Long bookId, String notes) throws WishlistException, UserException {
        User currentUser = userService.getCurrentUser();
        Wishlist wishlist = wishlistRepository.findByUserIdAndBookId(currentUser.getId(), bookId)
                .orElseThrow(() -> new WishlistException("Book is not in your wishlist"));

        wishlist.setNotes(notes);
        Wishlist updatedWishlist = wishlistRepository.save(wishlist);

        return wishlistMapper.toDTO(updatedWishlist);
    }

    @Override
    public Long getMyWishlistCount() throws UserException {
        User currentUser = userService.getCurrentUser();
        return wishlistRepository.countByUserId(currentUser.getId());
    }

    @Override
    public Long getBookWishlistCount(Long bookId) {
        return wishlistRepository.countByBookId(bookId);
    }
    private PageResponse<WishlistDTO> convertToPageResponse(Page<Wishlist> wishlistPage) {
        List<WishlistDTO> wishlistDTOs = wishlistPage.getContent()
                .stream()
                .map(wishlistMapper::toDTO)
                .collect(Collectors.toList());

        return new PageResponse<>(
                wishlistDTOs,
                wishlistPage.getNumber(),
                wishlistPage.getSize(),
                wishlistPage.getTotalElements(),
                wishlistPage.getTotalPages(),
                wishlistPage.isLast(),
                wishlistPage.isFirst(),
                wishlistPage.isEmpty()
        );
    }
}


