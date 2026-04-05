package com.nik.service;

import com.nik.exception.UserException;
import com.nik.payload.dto.UserDTO;
import com.nik.payload.response.AuthResponse;

public interface AuthService {
    AuthResponse login(String username, String password) throws UserException;
    AuthResponse signup(UserDTO req) throws UserException;
    void verifyEmailToken(String token, String email) throws UserException;
    void resendVerificationEmail(String email) throws UserException;
    void createPasswordResetToken(String email) throws UserException;
    void resetPassword(String token, String newPassword);
}
