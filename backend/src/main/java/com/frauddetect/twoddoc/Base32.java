package com.frauddetect.twoddoc;

/**
 * Base32 RFC 4648 sans remplissage, encodage de la signature dans un 2D-DOC.
 *
 * Implemente ici plutot qu'importe : le JDK n'expose pas Base32 et la seule
 * fonction utile tient en quelques lignes.
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    /**
     * @throws IllegalArgumentException si un caractere n'appartient pas a l'alphabet
     */
    static byte[] decode(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase();
        if (clean.isEmpty()) return new byte[0];

        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;

        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Caractere Base32 invalide");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[index++] = (byte) (buffer >> bitsLeft);
            }
        }
        return out;
    }
}
