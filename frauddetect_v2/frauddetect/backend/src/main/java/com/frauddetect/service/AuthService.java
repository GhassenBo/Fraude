package com.frauddetect.service;

import com.frauddetect.dto.AuthDto;
import com.frauddetect.entity.User;
import com.frauddetect.repository.UserRepository;
import com.frauddetect.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${app.free.documents}")
    private int freeLimit;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Email déjà utilisé");
        }

        User user = User.builder()
            .email(req.getEmail().toLowerCase().trim())
            .password(passwordEncoder.encode(req.getPassword()))
            .build();

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthDto.AuthResponse(token, AuthDto.UserInfo.from(user, freeLimit));
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
