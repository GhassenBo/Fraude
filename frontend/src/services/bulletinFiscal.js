/**
 * Donnees fiscales retenues d'une analyse de bulletin, pour alimenter le
 * rapprochement avec un avis d'imposition sans ressaisie.
 *
 * L'avis declare un net imposable annuel : c'est la seule grandeur commune aux
 * deux documents. Le net a payer, lui, est ampute du prelevement a la source et
 * ne se compare a rien sur l'avis.
 */

/** @returns null quand le bulletin ne porte aucune grandeur fiscale lisible. */
export function bulletinFiscal(documentInfo, source) {
  if (!documentInfo) return null;
  const { netImposable, cumulNetImposable, moisPeriode, periode } = documentInfo;
  if (!netImposable && !cumulNetImposable) return null;
  return { netImposable, cumulNetImposable, moisPeriode, periode, source };
}

/**
 * Dans un lot, le bulletin le plus avance dans l'annee : son cumul couvre le
 * plus de mois, donc lisse le mieux primes et treizieme mois.
 */
export function bulletinFiscalDuLot(batchResult) {
  const results = batchResult?.results;
  if (!Array.isArray(results)) return null;

  let meilleur = null;
  results.forEach((r, i) => {
    const candidat = bulletinFiscal(r?.documentInfo, batchResult.filenames?.[i]);
    if (!candidat) return;
    if (!meilleur || (candidat.moisPeriode || 0) > (meilleur.moisPeriode || 0)) {
      meilleur = candidat;
    }
  });
  return meilleur;
}

/** Tolerance admise entre le cumul ramene au mois et le net imposable du mois. */
const ECART_CUMUL_MIN = 0.85;
const ECART_CUMUL_MAX = 1.25;

/**
 * Montant a proposer, et d'ou il vient.
 *
 * Le cumul depuis janvier ramene au mois est prefere au montant du mois : il
 * integre primes et treizieme mois, que l'avis compte mais qu'un mois isole
 * ignore. Il n'est retenu que s'il couvre bien les mois ecoules — une embauche
 * en cours d'annee le rend inferieur, et le diviser par le numero du mois
 * sous-estimerait le revenu, ce qui creuserait un ecart avec l'avis sur un
 * dossier honnete.
 *
 * L'origine est restituee a l'utilisateur : le montant reste modifiable, et il
 * doit pouvoir voir de quel bulletin il sort avant de lancer le rapprochement.
 */
export function suggestionNetImposable(bulletin) {
  if (!bulletin) return null;
  const { netImposable, cumulNetImposable, moisPeriode, periode } = bulletin;

  if (cumulNetImposable && moisPeriode) {
    const moyenne = cumulNetImposable / moisPeriode;
    const coherent = !netImposable
      || (moyenne >= netImposable * ECART_CUMUL_MIN
          && moyenne <= netImposable * ECART_CUMUL_MAX);
    if (coherent) {
      return {
        valeur: Math.round(moyenne * 100) / 100,
        origine: `moyenne mensuelle du cumul depuis janvier`
          + ` (${formate(cumulNetImposable)} € sur ${moisPeriode} mois)`,
      };
    }
  }

  if (netImposable) {
    return {
      valeur: netImposable,
      origine: `net imposable du bulletin${periode ? ` de ${periode}` : ''}`,
    };
  }
  return null;
}

function formate(montant) {
  return Math.round(montant).toLocaleString('fr-FR');
}
