package com.nik.controller;

import com.nik.exception.UserException;
import com.nik.payload.dto.UserDTO;
import com.nik.payload.request.EmailVerificationRequest;
import com.nik.payload.request.ForgotPasswordRequest;
import com.nik.payload.request.LoginRequest;
import com.nik.payload.request.ResetPasswordRequest;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.response.AuthResponse;
import com.nik.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signupHandler(
            @RequestBody @Valid UserDTO req) throws UserException {

        AuthResponse response = authService.signup(req);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse> verifyEmail(
            @RequestParam("token") String token,
            @RequestParam(value = "email", required = false) String email) throws UserException {

        authService.verifyEmailToken(token, email);
        ApiResponse response = new ApiResponse("Email verified successfully. You can now sign in.", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse> resendVerificationEmail(
            @Valid @RequestBody EmailVerificationRequest request) throws UserException {

        authService.resendVerificationEmail(request.getEmail());
        ApiResponse response = new ApiResponse("If your account is pending verification, a new verification link has been sent.", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> loginHandler(
            @Valid @RequestBody LoginRequest req) throws UserException {

        AuthResponse response = authService.login(req.getEmail(), req.getPassword());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) throws UserException {

        authService.createPasswordResetToken(request.getEmail());

        ApiResponse res = new ApiResponse(
                "A Reset link was sent to your email.", true
        );
        return ResponseEntity.ok(res);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getPassword());
        ApiResponse res = new ApiResponse(
                "Password reset successful", true
        );
        return ResponseEntity.ok(res);
    }
}
