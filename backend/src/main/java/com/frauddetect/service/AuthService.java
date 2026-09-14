package com.frauddetect.service;

import com.frauddetect.dto.AuthDto;
import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class AuthService {

    private static final int TOKEN_VALIDITY_HOURS = 24;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.free.documents}")
    private int freeLimit;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé");
        }

        String verificationToken = generateToken();

        User user = User.builder()
            .email(req.getEmail().toLowerCase().trim())
            .password(passwordEncoder.encode(req.getPassword()))
            .emailVerified(false)
            .verificationToken(verificationToken)
            .verificationTokenExpiresAt(LocalDateTime.now().plusHours(TOKEN_VALIDITY_HOURS))
            .build();

        userRepository.save(user);
        emailService.sendVerificationEmail(user, verificationToken);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthDto.AuthResponse(token, AuthDto.UserInfo.from(user, freeLimit));
    }

    public boolean verifyEmail(String token) {
        if (token == null || token.isBlank()) return false;

        User user = userRepository.findByVerificationToken(token).orElse(null);
        if (user == null) return false;

        if (!user.isVerificationTokenValid(LocalDateTime.now())) return false;

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiresAt(null);
        userRepository.save(user);
        return true;
    }

    public void resendVerification(User user) {
        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("Votre adresse est déjà vérifiée");
        }

        String verificationToken = generateToken();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiresAt(LocalDateTime.now().plusHours(TOKEN_VALIDITY_HOURS));
        userRepository.save(user);

        emailService.sendVerificationEmail(user, verificationToken);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail().toLowerCase().trim())
            .orElseThrow(() -> new IllegalArgumentException("Email ou mot de passe incorrect"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Email ou mot de passe incorrect");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthDto.AuthResponse(token, AuthDto.UserInfo.from(user, freeLimit));
    }

    public AuthDto.UserInfo me(User user) {
        return AuthDto.UserInfo.from(user, freeLimit);
    }
}
