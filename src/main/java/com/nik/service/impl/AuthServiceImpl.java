package com.nik.service.impl;

import com.nik.config.JwtProvider;
import com.nik.domain.UserRole;
import com.nik.exception.UserException;
import com.nik.mapper.UserMapper;
import com.nik.model.EmailVerificationToken;
import com.nik.model.Genre;
import com.nik.model.PasswordResetToken;
import com.nik.model.User;
import com.nik.payload.dto.UserDTO;
import com.nik.payload.response.AuthResponse;
import com.nik.repository.EmailVerificationTokenRepository;
import com.nik.repository.GenreRepository;
import com.nik.repository.PasswordResetTokenRepository;
import com.nik.repository.UserRepository;
import com.nik.service.AuthService;
import com.nik.service.EmailService;
import com.nik.service.WishlistService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final long EMAIL_VERIFICATION_EXPIRY_HOURS = 24;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final CustomUserImplementation customUserImplementation;
    private final WishlistService wishlistService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final EmailService emailService;
    private final GenreRepository genreRepository;

    @Value("${app.frontend.reset-url}")
    private String frontendResetUrl;

    @Value("${app.frontend.verify-url}")
    private String frontendVerifyUrl;

    @Override
    @Transactional
    public AuthResponse signup(UserDTO req) throws UserException {
        String normalizedEmail = normalizeEmail(req.getEmail());

        User existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser != null) {
            if (Boolean.TRUE.equals(existingUser.getVerified())) {
                throw new UserException("Email id already registered");
            }

            ensureVerificationEmail(existingUser, false);

            AuthResponse response = new AuthResponse();
            response.setTitle("Verify your email");
            response.setMessage("Your account is pending verification. A fresh verification link has been sent to your email.");
            response.setUser(UserMapper.toDTO(existingUser));
            return response;
        }

        if (req.getRole() != null && req.getRole() != UserRole.ROLE_USER) {
            throw new UserException("Only ROLE_USER signup is allowed");
        }

        String normalizedPhone = req.getPhone() == null ? null : req.getPhone().trim();
        if (normalizedPhone != null && normalizedPhone.isBlank()) {
            normalizedPhone = null;
        }
        if (normalizedPhone != null && !normalizedPhone.matches("^[0-9]{10,15}$")) {
            throw new UserException("Phone number must be between 10 and 15 digits");
        }
        User createdUser = new User();
        createdUser.setEmail(normalizedEmail);
        createdUser.setPassword(passwordEncoder.encode(req.getPassword()));
        createdUser.setPhone(normalizedPhone);
        createdUser.setFullName(req.getFullName());
        createdUser.setLastLogin(LocalDateTime.now());
        createdUser.setCreatedAt(LocalDateTime.now());
        createdUser.setRole(UserRole.ROLE_USER);
        createdUser.setVerified(false);
        createdUser.setInterestGenres(resolveInterestGenres(req.getInterestGenreIds()));

        User savedUser = userRepository.save(createdUser);
        ensureVerificationEmail(savedUser, false);

        AuthResponse response = new AuthResponse();
        response.setTitle("Verify your email");
        response.setMessage("Account created. Please check your email and click the verification link before signing in.");
        response.setUser(UserMapper.toDTO(savedUser));
        return response;
    }

    @Override
    public AuthResponse login(String username, String password) throws UserException {
        String normalizedEmail = normalizeEmail(username);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (user == null) {
            throw new UserException("email id doesn't exist " + normalizedEmail);
        }
        if (!Boolean.TRUE.equals(user.getVerified())) {
            throw new UserException("Please verify your email before logging in.");
        }

        Authentication authentication = authenticate(normalizedEmail, password);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String token = jwtProvider.generateToken(authentication);

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        AuthResponse response = new AuthResponse();
        response.setTitle("Login success");
        response.setMessage("Welcome Back " + normalizedEmail);
        response.setJwt(token);
        response.setUser(UserMapper.toDTO(user));

        return response;
    }

    @Override
    @Transactional
    public void verifyEmailToken(String token, String email) throws UserException {
        if (token == null || token.trim().isEmpty()) {
            throw new UserException("Verification token is required");
        }

        String rawToken = token.trim();
        String tokenHash = hashVerificationToken(rawToken);

        Optional<EmailVerificationToken> optionalVerificationToken =
                emailVerificationTokenRepository.findByTokenHash(tokenHash);

        if (optionalVerificationToken.isEmpty()) {
            if (email != null && !email.trim().isEmpty()) {
                User user = userRepository.findByEmailIgnoreCase(normalizeEmail(email));
                if (user != null && Boolean.TRUE.equals(user.getVerified())) {
                    return;
                }
            }
            throw new UserException("Invalid or expired verification link");
        }

        EmailVerificationToken verificationToken = optionalVerificationToken.get();

        if (verificationToken.isExpired()) {
            emailVerificationTokenRepository.delete(verificationToken);
            throw new UserException("Invalid or expired verification link");
        }

        User user = verificationToken.getUser();
        if (Boolean.TRUE.equals(user.getVerified())) {
            return;
        }
        user.setVerified(true);
        userRepository.save(user);
        emailVerificationTokenRepository.delete(verificationToken);
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) throws UserException {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (user == null) {
            throw new UserException("user not found with given email");
        }
        if (Boolean.TRUE.equals(user.getVerified())) {
            throw new UserException("Email is already verified. Please sign in.");
        }

        ensureVerificationEmail(user, true);
    }

    public Authentication authenticate(String email, String password) throws UserException {
        UserDetails userDetails = customUserImplementation.loadUserByUsername(email);
        if (userDetails == null) {
            throw new UserException("email id doesn't exist " + email);
        }
        if (!passwordEncoder.matches(password, userDetails.getPassword())) {
            throw new UserException("Wrong Password ");
        }
        return new UsernamePasswordAuthenticationToken(email, null, userDetails.getAuthorities());
    }

    private String normalizeEmail(String email) throws UserException {
        if (email == null || email.trim().isEmpty()) {
            throw new UserException("Email is mandatory");
        }
        return email.trim().toLowerCase();
    }

    @Transactional
    public void createPasswordResetToken(String email) throws UserException {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(email));

        if (user == null) {
            throw new UserException("user not found with given email");
        }

        passwordResetTokenRepository.deleteAllByExpiryDateBefore(LocalDateTime.now());
        Optional<PasswordResetToken> existingToken = passwordResetTokenRepository.findByUser(user);
        if (existingToken.isPresent() && !existingToken.get().isExpired()) {
            return;
        }

        existingToken.ifPresent(passwordResetTokenRepository::delete);

        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiryDate(LocalDateTime.now().plusMinutes(5))
                .build();

        passwordResetTokenRepository.save(resetToken);

        String resetLink = frontendResetUrl + token;
        String subject = "Password Reset Request";
        String body = "You requested to reset your password. Use this link (valid 5 minutes): " + resetLink;

        emailService.sendEmail(user.getEmail(), subject, body);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        Optional<PasswordResetToken> optionalToken = passwordResetTokenRepository.findByToken(token);
        if (optionalToken.isEmpty()) {
            throw new BadCredentialsException("Invalid or expired token");
        }

        PasswordResetToken resetToken = optionalToken.get();

        if (resetToken.isExpired()) {
            passwordResetTokenRepository.delete(resetToken);
            throw new BadCredentialsException("Invalid or expired token");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        passwordResetTokenRepository.delete(resetToken);
    }

    private void ensureVerificationEmail(User user, boolean forceResend) throws UserException {
        emailVerificationTokenRepository.deleteAllByExpiryDateBefore(LocalDateTime.now());

        Optional<EmailVerificationToken> existingToken = emailVerificationTokenRepository.findByUser(user);
        if (!forceResend && existingToken.isPresent() && !existingToken.get().isExpired()) {
            return;
        }

        String token = UUID.randomUUID().toString();
        String tokenHash = hashVerificationToken(token);
        EmailVerificationToken verificationToken = existingToken
                .orElseGet(() -> EmailVerificationToken.builder()
                        .user(user)
                        .build());

        verificationToken.setTokenHash(tokenHash);
        verificationToken.setToken(tokenHash);
        verificationToken.setExpiryDate(LocalDateTime.now().plusHours(EMAIL_VERIFICATION_EXPIRY_HOURS));

        emailVerificationTokenRepository.save(verificationToken);

        String verificationLink = frontendVerifyUrl
                + token
                + "&email="
                + URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8);
        String subject = "Verify your email address";
        String body = "Welcome to Nik's Library. Please verify your email by clicking this link within 24 hours: "
                + verificationLink;

        emailService.sendEmail(user.getEmail(), subject, body);
    }

    private String hashVerificationToken(String token) throws UserException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte hashedByte : hashedBytes) {
                builder.append(String.format("%02x", hashedByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new UserException("Unable to process verification token");
        }
    }

    private LinkedHashSet<Genre> resolveInterestGenres(List<Long> interestGenreIds) throws UserException {
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


