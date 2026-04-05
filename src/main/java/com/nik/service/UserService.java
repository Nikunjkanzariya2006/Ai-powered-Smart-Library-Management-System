package com.nik.service;


import com.nik.domain.UserRole;
import com.nik.exception.UserException;
import com.nik.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;
//import com.nik.payload.request.UpdateUserDto;


public interface UserService {
	User getUserByEmail(String email) throws UserException;
	User getUserFromJwtToken(String jwt) throws UserException;
	User getUserById(Long id) throws UserException;
	Set<User> getUserByRole(UserRole role) throws UserException;
	Page<User> getUsers(Pageable pageable) throws UserException;
	User getCurrentUser() throws UserException;
	User completeOAuthProfile(String jwt, String fullName, String phone, java.util.List<Long> interestGenreIds) throws UserException;
	void deleteUserById(Long userId) throws UserException;

	/**
	 * Get total count of all registered users (Admin only)
	 */
	long getTotalUserCount();
}

