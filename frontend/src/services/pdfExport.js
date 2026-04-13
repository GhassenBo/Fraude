import { jsPDF } from 'jspdf';

// ── Color palette (printer-friendly light theme) ──
const C = {
  text:    [25,  30,  45],
  muted:   [100, 110, 130],
  accent:  [20,  130, 190],
  green:   [15,  150, 75],
  orange:  [210, 115, 0],
  red:     [195, 40,  55],
  purple:  [110, 70,  200],
  bg2:     [245, 247, 251],
  border:  [215, 220, 232],
};

const W = 210;
const M = 20; // margin

/**
 * Sanitize text for jsPDF/Helvetica which only supports Latin-1 (ISO-8859-1).
 * - Strips diacritics via NFD decomposition (é→e, ô→o, ç→c, etc.)
 * - Replaces unsupported symbols with ASCII equivalents
 */
function s(str) {
  if (str == null) return '';
  return String(str)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')   // strip combining diacritical marks
    .replace(/[—–]/g, '-')
    .replace(/['']/g, "'")
    .replace(/[""«»]/g, '"')
    .replace(/✓/g, '+')
    .replace(/✕/g, 'x')
    .replace(/✦/g, '*')
    .replace(/[^\x00-\xFF]/g, '?');    // fallback for remaining non-Latin-1
}

function scoreColor(color) {
  if (color === 'green')  return C.green;
  if (color === 'orange') return C.orange;
  return C.red;
}

function statusColor(status) {
  if (status === 'OK')      return C.green;
  if (status === 'WARNING') return C.orange;
  return C.red;
}

function statusIcon(status) {
  if (status === 'OK')      return '✓';
  if (status === 'WARNING') return '!';
  return '✕';
}

function drawHeader(doc, subtitle) {
  doc.setFillColor(...C.accent);
  doc.rect(0, 0, W, 14, 'F');

  doc.setFont('helvetica', 'bold');
  doc.setFontSize(11);
  doc.setTextColor(255, 255, 255);
  doc.text('FRAUDDETECT', M, 9.5);

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8);
  doc.text(s(subtitle), W - M, 9.5, { align: 'right' });
}

function drawFooter(doc) {
  doc.setDrawColor(...C.border);
  doc.line(M, 287, W - M, 287);
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(7.5);
  doc.setTextColor(...C.muted);
  doc.text(
    s('Genere par FraudDetect - Ce rapport est fourni a titre indicatif et ne constitue pas un avis juridique.'),
    M, 292
  );
  doc.text(s(new Date().toLocaleDateString('fr-FR', { day: '2-digit', month: 'long', year: 'numeric' })), W - M, 292, { align: 'right' });
}

function newPage(doc) {
  doc.addPage();
  drawHeader(doc, "Rapport d'analyse (suite)");
  return 24;
}

// ── Single document report ──
export function exportAnalysisPdf(result, filename) {
  const doc = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' });
  drawHeader(doc, 'Rapport d\'analyse de bulletin de salaire');
  let y = 24;

  // Document name
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8);
  doc.setTextColor(...C.muted);
  doc.text('Document analysé', M, y);
  y += 5;
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(12);
  doc.setTextColor(...C.text);
  doc.text(s(filename || 'bulletin.pdf'), M, y);
  y += 12;

  // Score block
  const sc = scoreColor(result.color);
  doc.setFillColor(...C.bg2);
  doc.roundedRect(M, y, W - 2 * M, 28, 3, 3, 'F');
  doc.setDrawColor(...C.border);
  doc.roundedRect(M, y, W - 2 * M, 28, 3, 3, 'S');

  // Score circle area
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(30);
  doc.setTextColor(...sc);
  doc.text(String(result.score), M + 24, y + 19, { align: 'center' });
  doc.setFontSize(8);
  doc.setTextColor(...C.muted);
  doc.text('/ 100', M + 24, y + 25, { align: 'center' });

  // Verdict + description
  doc.setFontSize(16);
  doc.setFont('helvetica', 'bold');
  doc.setTextColor(...sc);
  doc.text(s(result.verdict), M + 40, y + 12);

  const desc = result.verdict === 'AUTHENTIQUE'
    ? "Le document presente les caracteristiques d'un bulletin authentique."
    : result.verdict === 'SUSPECT'
    ? 'Des anomalies ont ete detectees. Verification manuelle recommandee.'
    : 'Plusieurs indicateurs critiques de fraude ont ete identifies.';
  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8.5);
  doc.setTextColor(...C.muted);
  const descLines = doc.splitTextToSize(desc, W - M - 40 - 15);
  doc.text(descLines, M + 40, y + 20);
  y += 36;

  // Document info
  if (result.documentInfo) {
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(8);
    doc.setTextColor(...C.accent);
    doc.text('INFORMATIONS DU DOCUMENT', M, y);
    y += 6;

    const fields = [
      ['Employeur',    result.documentInfo.employeur],
      ['SIRET',        result.documentInfo.siret],
      ['Employé',      result.documentInfo.employe],
      ['Période',      result.documentInfo.periode],
      ['Salaire brut', result.documentInfo.salaireBrut],
      ['Net à payer',  result.documentInfo.salaireNet],
    ].filter(([, v]) => v);

    const colW = (W - 2 * M) / 2;
    fields.forEach(([label, value], i) => {
      const col = i % 2;
      const row = Math.floor(i / 2);
      const x = M + col * colW;
      const ry = y + row * 13;
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(...C.muted);
      doc.text(s(label), x, ry);
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(9);
      doc.setTextColor(...C.text);
      doc.text(s(String(value || '-')), x, ry + 5);
    });
    y += Math.ceil(fields.length / 2) * 13 + 6;
  }

  // Separator
  doc.setDrawColor(...C.border);
  doc.line(M, y, W - M, y);
  y += 8;

  // Checks
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8);
  doc.setTextColor(...C.accent);
  doc.text('RÉSULTATS DES CONTRÔLES', M, y);
  y += 8;

  const categories = [...new Set(result.checks?.map(c => c.category) || [])];
  const AI_CAT = 'Analyse IA';
  const ordered = [...categories.filter(c => c !== AI_CAT), ...categories.filter(c => c === AI_CAT)];

  for (const cat of ordered) {
    if (y > 262) y = newPage(doc);

    const isAI = cat === AI_CAT;
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(7.5);
    doc.setTextColor(...(isAI ? C.purple : C.muted));
    doc.text((isAI ? '*  ' : '') + s(cat.toUpperCase()), M, y);
    y += 5;

    for (const check of result.checks.filter(c => c.category === cat)) {
      if (y > 270) y = newPage(doc);

      const ic = statusIcon(check.status);
      const tc = statusColor(check.status);

      // Status icon
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(...tc);
      doc.text(ic, M + 2, y);

      // Label
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(8);
      doc.setTextColor(...C.text);
      doc.text(s(check.label || ''), M + 8, y);

      // Detail
      if (check.detail) {
        doc.setFont('helvetica', 'normal');
        doc.setFontSize(7.5);
        doc.setTextColor(...C.muted);
        const lines = doc.splitTextToSize(s(check.detail), W - M - 8 - M);
        doc.text(lines, M + 8, y + 4.5);
        y += 4.5 + lines.length * 3.8 + 3;
      } else {
        y += 7;
      }
    }
    y += 3;
  }

  drawFooter(doc);
  doc.save(`frauddetect-${(filename || 'analyse').replace('.pdf', '')}.pdf`);
}

// ── Batch dossier report ──
export function exportBatchPdf(batchResult) {
  const doc = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' });
  drawHeader(doc, 'Rapport de dossier complet');
  let y = 24;

  // Global score block
  const sc = scoreColor(batchResult.globalColor);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8);
  doc.setTextColor(...C.muted);
  doc.text('SCORE GLOBAL DU DOSSIER', M, y);
  y += 5;

  doc.setFillColor(...C.bg2);
  doc.roundedRect(M, y, W - 2 * M, 32, 3, 3, 'F');
  doc.setDrawColor(...C.border);
  doc.roundedRect(M, y, W - 2 * M, 32, 3, 3, 'S');

  doc.setFont('helvetica', 'bold');
  doc.setFontSize(32);
  doc.setTextColor(...sc);
  doc.text(String(batchResult.globalScore), M + 26, y + 22, { align: 'center' });
  doc.setFontSize(8);
  doc.setTextColor(...C.muted);
  doc.text('/ 100', M + 26, y + 28, { align: 'center' });

  doc.setFontSize(18);
  doc.setFont('helvetica', 'bold');
  doc.setTextColor(...sc);
  doc.text(s(batchResult.globalVerdict), M + 44, y + 14);

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(9);
  doc.setTextColor(...C.muted);
  doc.text(s(`${batchResult.documentsAnalyzed} document(s) analyse(s)`), M + 44, y + 22);
  doc.text('Score = minimum des scores individuels', M + 44, y + 28);
  y += 40;

  // Separator
  doc.setDrawColor(...C.border);
  doc.line(M, y, W - M, y);
  y += 8;

  // Individual results
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8);
  doc.setTextColor(...C.accent);
  doc.text('DÉTAIL PAR DOCUMENT', M, y);
  y += 8;

  batchResult.results.forEach((result, i) => {
    if (y > 255) y = newPage(doc);

    const c = scoreColor(result.color);
    const fname = batchResult.filenames?.[i] || `Document ${i + 1}`;
    const failed   = result.checks?.filter(ch => ch.status === 'FAILED').length   || 0;
    const warnings = result.checks?.filter(ch => ch.status === 'WARNING').length  || 0;
    const ok       = result.checks?.filter(ch => ch.status === 'OK').length       || 0;

    doc.setFillColor(...C.bg2);
    doc.roundedRect(M, y, W - 2 * M, 22, 2, 2, 'F');
    doc.setDrawColor(...C.border);
    doc.roundedRect(M, y, W - 2 * M, 22, 2, 2, 'S');

    // Score pill
    doc.setFillColor(...c);
    doc.roundedRect(W - M - 22, y + 4, 22, 14, 2, 2, 'F');
    doc.setFont('helvetica', 'bold');
    doc.setFontSize(12);
    doc.setTextColor(255, 255, 255);
    doc.text(String(result.score), W - M - 11, y + 13, { align: 'center' });

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(9);
    doc.setTextColor(...C.text);
    doc.text(s(`${i + 1}. ${fname}`), M + 5, y + 10);

    doc.setFont('helvetica', 'normal');
    doc.setFontSize(8);
    doc.setTextColor(...c);
    doc.text(s(result.verdict), M + 5, y + 17);

    doc.setFontSize(7.5);
    doc.setTextColor(...C.muted);
    doc.text(
      `${ok} OK  ·  ${warnings} avert.  ·  ${failed} échec(s)`,
      M + 50, y + 17
    );
    y += 28;
  });

  // Checks per document (detail pages)
  batchResult.results.forEach((result, i) => {
    doc.addPage();
    drawHeader(doc, `Document ${i + 1} / ${batchResult.documentsAnalyzed}`);
    y = 24;

    const fname = batchResult.filenames?.[i] || `Document ${i + 1}`;
    const sc2 = scoreColor(result.color);

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(11);
    doc.setTextColor(...C.text);
    doc.text(s(fname), M, y);
    y += 6;

    doc.setFont('helvetica', 'bold');
    doc.setFontSize(20);
    doc.setTextColor(...sc2);
    doc.text(s(`${result.score} - ${result.verdict}`), M, y);
    y += 10;

    doc.setDrawColor(...C.border);
    doc.line(M, y, W - M, y);
    y += 8;

    const categories = [...new Set(result.checks?.map(c => c.category) || [])];
    for (const cat of categories) {
      if (y > 262) y = newPage(doc);

      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.5);
      doc.setTextColor(...C.muted);
      doc.text(s(cat.toUpperCase()), M, y);
      y += 5;

      for (const check of result.checks.filter(c => c.category === cat)) {
        if (y > 270) y = newPage(doc);
        const ic2 = statusIcon(check.status);
        const tc2 = statusColor(check.status);
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8);
        doc.setTextColor(...tc2);
        doc.text(ic2, M + 2, y);
        doc.setFont('helvetica', 'bold');
        doc.setFontSize(8);
        doc.setTextColor(...C.text);
        doc.text(s(check.label || ''), M + 8, y);
        if (check.detail) {
          doc.setFont('helvetica', 'normal');
          doc.setFontSize(7.5);
          doc.setTextColor(...C.muted);
          const lines = doc.splitTextToSize(s(check.detail), W - M - 8 - M);
          doc.text(lines, M + 8, y + 4.5);
          y += 4.5 + lines.length * 3.8 + 3;
        } else {
          y += 7;
        }
      }
      y += 3;
    }
    drawFooter(doc);
  });

  drawFooter(doc);
  doc.save(`frauddetect-dossier-${new Date().toISOString().slice(0, 10)}.pdf`);
}
