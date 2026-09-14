package com.frauddetect.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Plan plan = Plan.FREE;

    @Column(nullable = false)
    @Builder.Default
    private Integer documentsUsed = 0;

    // Email verification.
    // NULL identifie les comptes crees avant l'introduction de la verification :
    // ils sont consideres comme verifies pour ne pas bloquer les utilisateurs existants.
    private Boolean emailVerified;

    private String verificationToken;
    private LocalDateTime verificationTokenExpiresAt;

    // Stripe
    private String stripeCustomerId;
    private String stripeSubscriptionId;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime proSince;

    public enum Plan { FREE, PRO }

    public boolean isEmailVerified() {
        return emailVerified == null || emailVerified;
    }

    public boolean isVerificationTokenValid(LocalDateTime now) {
        return verificationTokenExpiresAt != null && now.isBefore(verificationTokenExpiresAt);
    }

    public boolean canAnalyze(int freeLimit) {
        return plan == Plan.PRO || documentsUsed < freeLimit;
    }

    public boolean canAnalyzeMultiple(int count, int freeLimit) {
        return plan == Plan.PRO || (documentsUsed + count) <= freeLimit;
    }

    public int remainingFreeDocuments(int freeLimit) {
        if (plan == Plan.PRO) return -1; // unlimited
        return Math.max(0, freeLimit - documentsUsed);
    }
}
