package com.nik.service.impl;


import com.nik.config.JwtProvider;
import com.nik.domain.UserRole;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.UserException;
import com.nik.model.Genre;
import com.nik.model.User;
import com.nik.repository.GenreRepository;
import com.nik.repository.UserRepository;
import com.nik.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {


	private final UserRepository userRepository;

	private final JwtProvider jwtProvider;

	private final GenreRepository genreRepository;


	@Override
	public User getUserByEmail(String email) throws UserException {
		User user=userRepository.findByEmail(email);
		if(user==null){
			throw new UserException("User not found with email: "+email);
		}
		return user;
	}

	@Override
	public User getUserFromJwtToken(String jwt) throws UserException {
		try {
			String email = jwtProvider.getEmailFromJwtToken(jwt);
			User user = userRepository.findByEmailIgnoreCase(email);
			if(user==null) throw new UserException("user not exist with email " + email);
			return user;
		} catch (UserException e) {
			throw e;
		} catch (Exception e) {
			throw new UserException("Invalid or expired token");
		}
	}

	@Override
	public User getUserById(Long id) throws UserException {
		return userRepository.findById(id)
				.orElseThrow(() -> new UserException("User not found with id: " + id));
	}

	@Override
	public Set<User> getUserByRole(UserRole role) throws UserException {
		return userRepository.findByRole(role);
	}

	@Override
	public User getCurrentUser() {
		if (SecurityContextHolder.getContext().getAuthentication() == null
				|| !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
			throw new AuthenticationFailureException("User is not authenticated");
		}
		String email = SecurityContextHolder.getContext().getAuthentication().getName();
		User user= userRepository.findByEmail(email);
		if(user == null) {
			throw new AuthenticationFailureException("Authenticated user could not be resolved");
		}
		return user;
	}



	@Override
	public Page<User> getUsers(Pageable pageable) throws UserException {
		return userRepository.findAll(pageable);
	}

	@Override
	public User completeOAuthProfile(String jwt, String fullName, String phone, List<Long> interestGenreIds) throws UserException {
		User user = getUserFromJwtToken(jwt);
		if (fullName != null) {
			String normalizedFullName = fullName.trim();
			if (normalizedFullName.isEmpty()) {
				throw new UserException("Full name is required");
			}
			user.setFullName(normalizedFullName);
		}
		String normalizedPhone = phone == null ? "" : phone.trim();

		if (normalizedPhone.isEmpty()) {
			user.setPhone(null);
		} else {
			if (!normalizedPhone.matches("^[0-9]{10,15}$")) {
				throw new UserException("Phone number must be between 10 and 15 digits");
			}

			if (userRepository.existsByPhoneAndIdNot(normalizedPhone, user.getId())) {
				throw new UserException("Phone number already in use");
			}

			user.setPhone(normalizedPhone);
		}

		if (interestGenreIds != null) {
			user.setInterestGenres(resolveInterestGenres(interestGenreIds));
		}

		return userRepository.save(user);
	}

	@Override
	@Transactional
	public void deleteUserById(Long userId) throws UserException {
		User user = userRepository.findById(userId)
				.orElseThrow(() -> new UserException("User not found with id: " + userId));
		user.getInterestGenres().clear();
		userRepository.save(user);
		userRepository.flush();
		userRepository.delete(user);
	}

	@Override
	public long getTotalUserCount() {
		return userRepository.count();
	}

	private Set<Genre> resolveInterestGenres(List<Long> interestGenreIds) throws UserException {
		if (interestGenreIds == null || interestGenreIds.isEmpty()) {
			return new LinkedHashSet<>();
		}

		List<Long> uniqueIds = interestGenreIds.stream()
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();

		List<Genre> genres = genreRepository.findByIdInAndActiveTrueOrderByDisplayOrderAsc(uniqueIds);
		if (genres.size() != uniqueIds.size()) {
			throw new UserException("One or more selected interests are invalid or inactive");
		}
		if (genres.stream().anyMatch(genre -> genre.getParentGenre() != null)) {
			throw new UserException("Please select only main interest topics");
		}

		return new LinkedHashSet<>(genres);
	}

}


