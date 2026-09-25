package com.frauddetect.util;

/**
 * Origine du frontend, deduite de FRONTEND_URL.
 *
 * Cette variable sert aussi a configurer CORS et peut donc porter plusieurs
 * origines separees par des virgules — la valeur recommandee couvre le domaine
 * avec et sans www. Toute adresse construite par concatenation doit donc n'en
 * retenir qu'une, sans quoi elle devient invalide.
 *
 * Deux services s'en chargeaient chacun de son cote et un troisieme l'ignorait :
 * StripeService fabriquait les URL de retour de paiement a partir de la liste
 * entiere, produisant une adresse que Stripe refuse. La creation de la session
 * de paiement echouait alors integralement, avec pour seul symptome une erreur
 * au moment de s'abonner.
 */
public final class FrontendUrl {

    private FrontendUrl() {
    }

    /**
     * Premiere origine de la liste, sans barre oblique finale.
     *
     * @return une chaine vide si la valeur est absente, jamais null : une adresse
     *         relative vaut mieux qu'une concatenation avec "null"
     */
    public static String firstOrigin(String configured) {
        if (configured == null || configured.isBlank()) return "";
        String first = configured.split(",")[0].trim();
        return first.endsWith("/") ? first.substring(0, first.length() - 1) : first;
    }
}
