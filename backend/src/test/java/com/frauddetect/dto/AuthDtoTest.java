package com.frauddetect.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetect.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private User user(User.Plan plan, int used) {
        return User.builder().id(1L).email("test@example.com").password("x")
            .plan(plan).documentsUsed(used).build();
    }

    @Test
    void indicateurProExposeSousLeNomAttenduParLeFrontend() throws Exception {
        // Sans annotation, Lombok nomme l'accesseur isPro() et Jackson emet
        // "pro" : le frontend lisait alors undefined et affichait un compte
        // gratuit avec un quota negatif.
        String json = mapper.writeValueAsString(
            AuthDto.UserInfo.from(user(User.Plan.PRO, 15), 10));

        assertThat(json).contains("\"isPro\":true");
    }

    @Test
    void comptePro_quotaIllimite() {
        AuthDto.UserInfo info = AuthDto.UserInfo.from(user(User.Plan.PRO, 15), 10);

        assertThat(info.isPro()).isTrue();
        // -1 signifie illimite : l'interface ne doit pas l'afficher tel quel.
        assertThat(info.getRemainingDocuments()).isEqualTo(-1);
    }

    @Test
    void compteGratuit_quotaJamaisNegatif() {
        AuthDto.UserInfo info = AuthDto.UserInfo.from(user(User.Plan.FREE, 15), 10);

        assertThat(info.isPro()).isFalse();
        assertThat(info.getRemainingDocuments()).isZero();
    }
}
