package com.nik.controller;

import com.nik.exception.UserException;
import com.nik.mapper.UserMapper;
import com.nik.model.User;


import com.nik.payload.dto.UserDTO;
import com.nik.payload.request.CompleteProfileRequest;
import com.nik.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;

	

	
	@GetMapping("/api/users/profile")
	public ResponseEntity<UserDTO> getUserProfileFromJwtHandler(
			@RequestHeader("Authorization") String jwt) throws UserException {
		User user = userService.getUserFromJwtToken(jwt);
		UserDTO userDTO=UserMapper.toDTO(user);

		return new ResponseEntity<>(userDTO,HttpStatus.OK);
	}



	@GetMapping("/users/list")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Page<UserDTO>> getUsersListHandler(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size
	) throws UserException {
		Page<UserDTO> users = userService.getUsers(PageRequest.of(page, size))
				.map(UserMapper::toDTO);

		return new ResponseEntity<>(users,HttpStatus.OK);
	}

	@PutMapping("/api/users/complete-profile")
	@PreAuthorize("hasAnyRole('USER','ADMIN')")
	public ResponseEntity<UserDTO> completeProfile(
			@RequestHeader("Authorization") String jwt,
			@Valid @RequestBody CompleteProfileRequest request
	) throws UserException {
		User user = userService.completeOAuthProfile(
				jwt,
				request.getFullName(),
				request.getPhone(),
				request.getInterestGenreIds()
		);
		return new ResponseEntity<>(UserMapper.toDTO(user), HttpStatus.OK);
	}

}

