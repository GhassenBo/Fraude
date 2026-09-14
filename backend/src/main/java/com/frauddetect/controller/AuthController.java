package com.frauddetect.controller;

import com.frauddetect.dto.AuthDto;
import com.frauddetect.entity.User;
import com.frauddetect.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${app.base.url}")
    private String baseUrl;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody AuthDto.RegisterRequest req) {
        try {
            return ResponseEntity.ok(authService.register(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthDto.LoginRequest req) {
        try {
            return ResponseEntity.ok(authService.login(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(authService.me(user));
    }

    // Cible du lien envoye par email. Redirige vers le frontend, qui affiche le resultat.
    @GetMapping("/verify")
    public ResponseEntity<Void> verify(@RequestParam(required = false) String token) {
        boolean ok = authService.verifyEmail(token);
        String target = frontendOrigin() + "/?verified=" + (ok ? "1" : "0");
        return ResponseEntity.status(302)
            .header(HttpHeaders.LOCATION, URI.create(target).toString())
            .build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@AuthenticationPrincipal User user) {
        try {
            authService.resendVerification(user);
            return ResponseEntity.ok(Map.of("message", "Email de vérification envoyé"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // FRONTEND_URL peut contenir plusieurs origines separees par des virgules (CORS).
    private String frontendOrigin() {
        String first = baseUrl.split(",")[0].trim();
        return first.endsWith("/") ? first.substring(0, first.length() - 1) : first;
    }
}
