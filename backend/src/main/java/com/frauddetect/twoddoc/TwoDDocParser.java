package com.frauddetect.twoddoc;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoupe le contenu brut d'un 2D-DOC.
 *
 * Structure observee sur un avis d'imposition de production :
 *
 *   DC 04 FR06 FPE6 FFFF 25DC 28 <donnees> US <signature>
 *   |  |  |    |    |    |    |
 *   |  |  |    |    |    |    +-- type de document
 *   |  |  |    |    |    +------- date de signature
 *   |  |  |    |    +------------ date d'emission
 *   |  |  |    +----------------- identifiant du certificat
 *   |  |  +---------------------- autorite de certification
 *   |  +------------------------- version du catalogue
 *   +---------------------------- marqueur
 *
 * Les champs de donnees sont des couples identifiant sur deux caracteres puis
 * valeur, separes par GS. La signature suit le separateur US et est encodee en
 * Base32.
 *
 * Les dates sont exprimees en nombre de jours depuis le 1er janvier 2000, en
 * hexadecimal ; FFFF signifie non renseignee.
 */
@Component
public class TwoDDocParser {

    static final char GS = 0x1D;
    static final char US = 0x1F;

    private static final String MARKER = "DC";
    private static final LocalDate DATE_ORIGIN = LocalDate.of(2000, 1, 1);
    private static final String DATE_ABSENTE = "FFFF";

    /** Longueur de l'en-tete par version de catalogue. */
    private static final Map<String, Integer> HEADER_LENGTHS = Map.of(
        "01", 22,
        "02", 22,
        "03", 22,
        "04", 22
    );

    public TwoDDocData parse(String raw) {
        if (raw == null || raw.length() < 4 || !raw.startsWith(MARKER)) {
            return malformed(raw, "Marqueur 2D-DOC absent");
        }

        String version = raw.substring(2, 4);
        Integer headerLength = HEADER_LENGTHS.get(version);
        if (headerLength == null) {
            return malformed(raw, "Version de catalogue non prise en charge : " + version);
        }
        if (raw.length() < headerLength) {
            return malformed(raw, "En-tete tronque");
        }

        String header = raw.substring(0, headerLength);
        String body = raw.substring(headerLength);

        int usIndex = body.indexOf(US);
        if (usIndex < 0) {
            return malformed(raw, "Separateur de signature absent");
        }

        String dataZone = body.substring(0, usIndex);
        String signatureZone = body.substring(usIndex + 1);

        byte[] signature;
        try {
            signature = Base32.decode(signatureZone);
        } catch (IllegalArgumentException e) {
            return malformed(raw, "Signature non decodable en Base32");
        }

        return TwoDDocData.builder()
            .detected(true)
            .rawData(raw)
            .version(version)
            .authorityId(header.substring(4, 8))
            .certificateId(header.substring(8, 12))
            .emissionDate(parseDate(header.substring(12, 16)))
            .signatureDate(parseDate(header.substring(16, 20)))
            .documentType(header.substring(20, 22))
            .fields(parseFields(dataZone))
            // La signature couvre l'en-tete et la zone de donnees, separateur exclu.
            .signedPayload(header + dataZone)
            .signature(signature)
            .signatureStatus(SignatureStatus.NOT_VERIFIED)
            .build();
    }

    private Map<String, String> parseFields(String dataZone) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String segment : dataZone.split(String.valueOf(GS))) {
            if (segment.length() < 2) continue;
            // Un identifiant deja rencontre n'est pas ecrase : le premier prime.
            fields.putIfAbsent(segment.substring(0, 2), segment.substring(2).trim());
        }
        return fields;
    }

    private LocalDate parseDate(String hex) {
        if (DATE_ABSENTE.equalsIgnoreCase(hex)) return null;
        try {
            return DATE_ORIGIN.plusDays(Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TwoDDocData malformed(String raw, String detail) {
        return TwoDDocData.builder()
            .detected(raw != null)
            .rawData(raw)
            .fields(Map.of())
            .signatureStatus(SignatureStatus.MALFORMED)
            .signatureDetail(detail)
            .build();
    }
}
