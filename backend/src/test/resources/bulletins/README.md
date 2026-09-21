# Jeu de tests — bulletins de paie synthetiques

Cinq bulletins derives d'une meme reference, chacun portant **une seule**
alteration. Le scenario attendu est imprime sur chaque document, en bas de page.

Donnees entierement fictives : marquage « DOCUMENT FICTIF », SIRET
`000 000 000 00000` et NIR volontairement invalides. Aucune donnee personnelle.

| Fichier | Alteration par rapport a la reference | Attendu |
|---|---|---|
| `01_reference_calculs_coherents.pdf` | aucune | tous les calculs internes concordent |
| `02_net_a_payer_modifie.pdf` | `NET A PAYER` 2 728,35 → **3 728,35** | net superieur de 1 000 EUR au montant calculable |
| `03_total_brut_incoherent.pdf` | `TOTAL BRUT` 3 750,00 → **4 450,00** | brut different de salaire de base + prime |
| `04_cotisations_incoherentes.pdf` | `TOTAL COTISATIONS` 808,15 → **608,15** | total des retenues different de la somme des lignes |
| `05_employeur_incoherent.pdf` | employeur du bloc identite → `LABORATOIRE EXEMPLE SARL` | deux noms d'employeur divergents sur le meme document |
| `06_periode_cumuls_impossibles.pdf` | date de paiement → **31/02/2026** ; cumuls 2026 → **2025**, cumul brut 33 750,00 → **2 000,00** | date inexistante, cumul annuel inferieur au mois courant, annee des cumuls differente de la periode |
| `07_identite_et_zone_alteree.pdf` | salarie du recapitulatif → `Camille MODIFIE` ; `NET A PAYER` surcharge par un second texte | identite divergente au sein du document, zone visuellement retouchee |

Valeurs de la reference, utiles pour interpreter les ecarts :

```
Salaire de base           3 500,00      Net avant impot    2 941,85
Prime projet                250,00      Net imposable      3 050,00
TOTAL BRUT                3 750,00      PAS (7,00 %)         213,50
TOTAL COTISATIONS salarie   808,15      NET A PAYER        2 728,35
Assiette deplafonnee      3 750,00      Cumul brut 2026   33 750,00
```

`expected_results.csv` porte les signaux attendus par cas, un identifiant par
signal separe par `|`.

Le SIRET etant invalide par construction, le controle SIRET echoue sur les sept
documents, y compris la reference : ce n'est pas un faux positif mais une
propriete du jeu de tests.
