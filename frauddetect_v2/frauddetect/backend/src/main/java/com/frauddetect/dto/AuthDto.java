package com.frauddetect.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

public class AuthDto {

    @Data
    public static class RegisterRequest {
        @Email(message = "Email invalide")
        @NotBlank
        private String email;

        @Size(min = 8, message = "Mot de passe minimum 8 caractères")
        @NotBlank
        private String password;
    }

    @Data
    public static class LoginRequest {
        @NotBlank private String email;
        @NotBlank private String password;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private UserInfo user;

        public AuthResponse(String token, UserInfo user) {
            this.token = token;
            this.user = user;
        }
    }

    @Data
    public static class UserInfo {
        private Long id;
        private String email;
        private String plan;
        private int documentsUsed;
        private int remainingDocuments;
        private boolean isPro;

        public static UserInfo from(com.frauddetect.entity.User user, int freeLimit) {
            UserInfo info = new UserInfo();
            info.id = user.getId();
            info.email = user.getEmail();
            info.plan = user.getPlan().name();
            info.documentsUsed = user.getDocumentsUsed();
            info.remainingDocuments = user.remainingFreeDocuments(freeLimit);
            info.isPro = user.getPlan() == com.frauddetect.entity.User.Plan.PRO;
            return info;
        }
    }
}
