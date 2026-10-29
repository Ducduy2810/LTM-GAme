import fs from 'node:fs';
import path from 'node:path';
import { createCanvas, loadImage } from './pdf-tools/node_modules/@napi-rs/canvas/index.js';

const stage = 'scratch/thesis-docx';
const config = JSON.parse(fs.readFileSync('report/thong-tin-bia.json', 'utf8'));
const source = fs.readFileSync('report/bao-cao-so-bo.md', 'utf8');
fs.mkdirSync(stage + '/word/media', { recursive: true });
fs.mkdirSync(stage + '/word/_rels', { recursive: true });
fs.mkdirSync(stage + '/_rels', { recursive: true });
const esc = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const paragraph = (text, style = 'Normal', extra = '') => '<w:p><w:pPr><w:pStyle w:val="' + style + '"/>' + extra + '</w:pPr>' + inline(text) + '</w:p>';
function run(text, options = '') {
    return '<w:r><w:rPr>' + options + '</w:rPr><w:t xml:space="preserve">' + esc(text) + '</w:t></w:r>';
}
function inline(text) {
    const plain = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '$1 ($2)');
    return plain.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)/g).map(part => {
        if (part.startsWith('**')) return run(part.slice(2, -2), '<w:b/>');
        if (part.startsWith('*') && part.endsWith('*')) return run(part.slice(1, -1), '<w:i/>');
        if (part.startsWith('`')) return run(part.slice(1, -1));
        return run(part);
    }).join('');
}
const bookmarks = [], tableCaptions = [], figureCaptions = [];
let bookmarkId = 0;
function markedParagraph(text, style, collection = bookmarks, level = 1) {
    const id = ++bookmarkId, name = 'Report_' + id;
    collection.push({ text, name, level });
    return '<w:p><w:pPr><w:pStyle w:val="' + style + '"/></w:pPr><w:bookmarkStart w:id="' + id + '" w:name="' + name + '"/>' + inline(text) + '<w:bookmarkEnd w:id="' + id + '"/></w:p>';
}
function field(instruction, cached) {
    return '<w:p><w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r><w:r><w:instrText xml:space="preserve">' + esc(instruction) + '</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r></w:p>' + cached + '<w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>';
}
function contents(items, instruction) {
    const cached = items.map(item => '<w:p><w:pPr><w:pStyle w:val="TOC' + item.level + '"/><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="8788"/></w:tabs></w:pPr><w:hyperlink w:anchor="' + item.name + '">' + run(item.text) + '</w:hyperlink><w:r><w:tab/></w:r><w:fldSimple w:instr=" PAGEREF ' + item.name + ' \\h "><w:r><w:t>–</w:t></w:r></w:fldSimple></w:p>').join('');
    return field(instruction, cached);
}
const imageRels = [];
let imageId = 0;
function imageParagraph(filename, widthCm, heightCm) {
    const id = ++imageId, rel = 'rIdImage' + id;
    imageRels.push({ id: rel, target: 'media/' + filename });
    const cx = Math.round(widthCm * 360000), cy = Math.round(heightCm * 360000);
    return '<w:p><w:pPr><w:keepNext/><w:jc w:val="center"/><w:spacing w:before="0" w:after="120" w:line="240"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="' + cx + '" cy="' + cy + '"/><wp:docPr id="' + id + '" name="' + filename + '"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="' + filename + '"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="' + rel + '"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="' + cx + '" cy="' + cy + '"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>';
}

// Diagram text is rendered as a figure, so it remains readable in Word and in print.
const canvas = createCanvas(1320, 900), ctx = canvas.getContext('2d');
ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 1320, 900);
ctx.strokeStyle = '#111'; ctx.lineWidth = 2; ctx.fillStyle = '#111';
function box(x, y, w, h, lines) {
    ctx.strokeRect(x, y, w, h); ctx.textAlign = 'center'; ctx.font = '30px "Times New Roman"';
    lines.forEach((line, i) => ctx.fillText(line, x + w / 2, y + h / 2 + (i - (lines.length - 1) / 2) * 36 + 10));
}
function arrow(x1, y1, x2, y2) {
    ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
    const a = Math.atan2(y2 - y1, x2 - x1), len = 13;
    ctx.beginPath(); ctx.moveTo(x2, y2); ctx.lineTo(x2 - len * Math.cos(a - 0.4), y2 - len * Math.sin(a - 0.4)); ctx.moveTo(x2, y2); ctx.lineTo(x2 - len * Math.cos(a + 0.4), y2 - len * Math.sin(a + 0.4)); ctx.stroke();
}
box(355, 20, 610, 86, ['Các trình duyệt / Người chơi A, B, C...']);
arrow(660, 106, 660, 122); ctx.font = '28px "Times New Roman"'; ctx.fillText('HTTP + WebSocket / TCP, cổng 3000', 660, 155); arrow(660, 168, 660, 198);
box(300, 198, 720, 90, ['HttpAndWebSocketServer']);
arrow(420, 288, 210, 360); arrow(660, 288, 660, 360); arrow(900, 288, 1110, 360);
box(25, 360, 370, 105, ['Phiên và tài khoản', 'SessionManager']);
box(475, 360, 370, 105, ['Điều phối trò chơi', 'Game / Room']);
box(925, 360, 370, 105, ['Theo dõi kết nối', 'Ping / Pong / Timeout']);
arrow(660, 465, 660, 520); box(150, 520, 1020, 105, ['ArenaRoom | WheelRoom | SurvivalRoom', 'Luật chơi, timer và trạng thái trong bộ nhớ']);
arrow(480, 625, 340, 700);
box(80, 700, 520, 105, ['UserRepository / Leaderboard', 'SQLite: data/game.db']);
box(725, 700, 520, 105, ['PasswordResetMailer', 'SMTP qua TLS']);
ctx.beginPath(); ctx.moveTo(1020, 243); ctx.lineTo(1305, 243); ctx.lineTo(1305, 752); ctx.stroke(); arrow(1305, 752, 1245, 752);
ctx.font = '26px "Times New Roman"'; ctx.fillText('Máy chủ kiểm tra hành động và phát trạng thái tới các client trong phòng', 660, 868);
const figure = canvas.toBuffer('image/png');
fs.writeFileSync('report/hinh-2-1-kien-truc.png', figure);
fs.writeFileSync(stage + '/word/media/architecture.png', figure);

const logo = createCanvas(360, 360), lc = logo.getContext('2d');
if (config.logoPath) {
    const suppliedLogo = await loadImage(config.logoPath);
    lc.fillStyle = '#fff'; lc.fillRect(0, 0, 360, 360);
    const s = Math.min(360 / suppliedLogo.width, 360 / suppliedLogo.height);
    lc.drawImage(suppliedLogo, (360 - suppliedLogo.width * s) / 2, (360 - suppliedLogo.height * s) / 2, suppliedLogo.width * s, suppliedLogo.height * s);
} else {
    lc.fillStyle = '#fff'; lc.fillRect(0, 0, 360, 360); lc.strokeStyle = '#666'; lc.lineWidth = 2; lc.strokeRect(3, 3, 354, 354);
    lc.fillStyle = '#555'; lc.textAlign = 'center'; lc.font = '30px "Times New Roman"'; lc.fillText('LOGO TRƯỜNG', 180, 158); lc.fillText('2,5 × 2,5 cm', 180, 205);
}
fs.writeFileSync(stage + '/word/media/logo.png', logo.toBuffer('image/png'));

function section(kind) {
    let footer = '', number = '', border = '';
    if (kind === 'cover') border = '<w:pgBorders w:offsetFrom="text"><w:top w:val="double" w:sz="12" w:space="18" w:color="000000"/><w:left w:val="double" w:sz="12" w:space="18" w:color="000000"/><w:bottom w:val="double" w:sz="12" w:space="18" w:color="000000"/><w:right w:val="double" w:sz="12" w:space="18" w:color="000000"/></w:pgBorders>';
    if (kind === 'front') { footer = '<w:footerReference w:type="default" r:id="rIdFooterRoman"/>'; number = '<w:pgNumType w:fmt="lowerRoman" w:start="1"/>'; }
    if (kind === 'main') { footer = '<w:footerReference w:type="default" r:id="rIdFooterArabic"/>'; number = '<w:pgNumType w:fmt="decimal" w:start="1"/>'; }
    return '<w:sectPr>' + footer + '<w:type w:val="nextPage"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1701" w:right="1134" w:bottom="1701" w:left="1984" w:header="720" w:footer="850"/>' + border + number + '</w:sectPr>';
}
const sectionBreak = kind => '<w:p><w:pPr><w:spacing w:before="0" w:after="0"/>' + section(kind) + '</w:pPr></w:p>';
function cover(inner) {
    const center = '<w:jc w:val="center"/>';
    let xml = paragraph(config.ministry, 'CoverMinistry', center) + paragraph(config.school, 'CoverSchool', center);
    xml += imageParagraph('logo.png', 2.5, 2.5);
    if (!inner) xml += paragraph(config.documentType, 'CoverType', center);
    xml += paragraph(config.title, 'CoverTitle', center);
    xml += paragraph('Môn học: ' + config.subject, 'CoverSubject', center);
    for (const [label, value] of [['Người hướng dẫn', config.instructor], ['Sinh viên thực hiện', config.students], ['Mã số sinh viên', config.studentIds], ['Chuyên ngành', config.major], ['Khoa', config.department]]) {
        xml += paragraph(label + ': ' + value, 'CoverMeta');
    }
    xml += paragraph(config.location + ', năm ' + config.year, 'CoverYear', center);
    return xml + sectionBreak('cover');
}

let body = cover(false) + cover(true), isMain = false, firstFront = true, firstChapter = true;
const visible = source.split('<!-- REPORT:FRONT -->')[1];
if (!visible) throw new Error('Missing report front/main markers.');
const lines = visible.split(/\r?\n/);
for (let i = 0; i < lines.length; i++) {
    let line = lines[i];
    if (line === '<!-- REPORT:MAIN -->') { body += sectionBreak('front'); isMain = true; continue; }
    if (line === '<!-- REPORT:TOC -->') { body += '@@CONTENTS@@'; while (i + 1 < lines.length && !lines[i + 1].startsWith('# ')) i++; continue; }
    if (line === '<!-- REPORT:TABLE_LIST -->') { body += '@@TABLES@@'; while (i + 1 < lines.length && !lines[i + 1].startsWith('# ')) i++; continue; }
    if (line === '<!-- REPORT:FIGURE_LIST -->') { body += '@@FIGURES@@'; while (i + 1 < lines.length && !lines[i + 1].startsWith('# ')) i++; continue; }
    if (!line.trim() || line.startsWith('<!--')) continue;
    if (line.startsWith('```')) {
        let code = [];
        while (++i < lines.length && !lines[i].startsWith('```')) code.push(lines[i]);
        if (code.some(x => x.includes('HttpAndWebSocketServer'))) body += imageParagraph('architecture.png', 15.4, 10.5);
        else body += code.map(x => paragraph(x, 'Code')).join('');
        continue;
    }
    if (line.startsWith('|')) {
        const rows = [];
        while (i < lines.length && lines[i].startsWith('|')) {
            if (!/^\|[\s:|\-]+\|$/.test(lines[i])) rows.push(lines[i].trim().slice(1, -1).split('|').map(x => x.trim()));
            i++;
        }
        i--;
        const count = rows[0].length;
        const weights = count === 2 ? [0.34, 0.66] : count === 3 ? [0.29, 0.29, 0.42] : new Array(count).fill(1 / count);
        body += '<w:tbl><w:tblPr><w:tblW w:w="8788" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>' + ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(edge => '<w:' + edge + ' w:val="single" w:sz="4" w:color="000000"/>').join('') + '</w:tblBorders><w:tblCellMar><w:top w:w="70" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="70" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>' + weights.map(w => '<w:gridCol w:w="' + Math.round(8788 * w) + '"/>').join('') + '</w:tblGrid>';
        rows.forEach((cells, r) => {
            body += '<w:tr><w:trPr>' + (r === 0 ? '<w:tblHeader/>' : '') + '</w:trPr>';
            cells.forEach((cell, c) => body += '<w:tc><w:tcPr><w:tcW w:w="' + Math.round(8788 * weights[c]) + '" w:type="dxa"/></w:tcPr>' + paragraph(r === 0 ? '**' + cell + '**' : cell, 'TableText') + '</w:tc>');
            body += '</w:tr>';
        });
        body += '</w:tbl>' + paragraph('', 'TableGap');
        continue;
    }
    const caption = line.match(/^\*\*((Bảng|Hình) .+)\*\*$/);
    if (caption) { body += markedParagraph(caption[1], caption[2] === 'Bảng' ? 'TableCaption' : 'FigureCaption', caption[2] === 'Bảng' ? tableCaptions : figureCaptions); continue; }
    const heading = line.match(/^(#{1,4}) (.+)$/);
    if (heading) {
        const level = heading[1].length, text = heading[2];
        if (!isMain) {
            const style = firstFront ? 'FrontFirst' : text === 'MỤC LỤC' ? 'ContentsTitle' : 'FrontTitle';
            body += text === 'MỤC LỤC' ? paragraph(text, style) : markedParagraph(text, style);
            firstFront = false;
        } else if (level === 1) {
            body += markedParagraph(text, firstChapter ? 'ChapterFirst' : 'Heading1');
            firstChapter = false;
        } else {
            body += markedParagraph(text, level === 2 ? 'Heading2' : level === 3 ? 'Heading3' : 'Heading3', bookmarks, level === 2 ? 2 : 3);
        }
        continue;
    }
    if (line.startsWith('- ')) line = '• ' + line.slice(2);
    const refs = body.includes('TÀI LIỆU THAM KHẢO') && !body.includes('PHỤ LỤC');
    body += paragraph(line, refs ? 'Reference' : 'Normal');
}
body = body.replace('@@CONTENTS@@', contents(bookmarks, ' TOC \\o "1-3" \\t "FrontTitle,1,FrontFirst,1" \\h \\z '));
body = body.replace('@@TABLES@@', contents(tableCaptions, ' TOC \\t "TableCaption,1" \\h \\z '));
body = body.replace('@@FIGURES@@', contents(figureCaptions, ' TOC \\t "FigureCaption,1" \\h \\z '));
body += section('main');

const font = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman" w:cs="Times New Roman"/>';
function style(id, props, runProps = '', based = 'Normal') {
    return '<w:style w:type="paragraph" w:styleId="' + id + '"><w:name w:val="' + id + '"/>' + (id === 'Normal' ? '' : '<w:basedOn w:val="' + based + '"/>') + '<w:next w:val="Normal"/><w:pPr>' + props + '</w:pPr><w:rPr>' + font + runProps + '</w:rPr></w:style>';
}
const chapterPr = '<w:keepNext/><w:keepLines/><w:jc w:val="center"/><w:spacing w:before="640" w:after="640" w:line="360" w:lineRule="auto"/><w:outlineLvl w:val="0"/>';
const headingPr = '<w:keepNext/><w:keepLines/><w:jc w:val="both"/><w:spacing w:before="120" w:after="120" w:line="360" w:lineRule="auto"/>';
let styles = '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr>' + font + '<w:sz w:val="26"/><w:szCs w:val="26"/><w:lang w:val="vi-VN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:widowControl/><w:jc w:val="both"/><w:spacing w:before="0" w:after="0" w:line="360" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>';
styles += style('Normal', '<w:widowControl/><w:jc w:val="both"/><w:spacing w:before="0" w:after="0" w:line="360" w:lineRule="auto"/>', '<w:sz w:val="26"/>');
styles += style('Heading1', chapterPr + '<w:pageBreakBefore/>', '<w:b/><w:sz w:val="32"/>');
styles += style('ChapterFirst', chapterPr, '<w:b/><w:sz w:val="32"/>');
styles += style('Heading2', headingPr + '<w:outlineLvl w:val="1"/>', '<w:b/><w:sz w:val="28"/>');
styles += style('Heading3', headingPr + '<w:outlineLvl w:val="2"/>', '<w:sz w:val="26"/>');
styles += style('Heading4', headingPr + '<w:outlineLvl w:val="3"/>', '<w:i/><w:sz w:val="26"/>');
for (const id of ['FrontTitle', 'ContentsTitle', 'FrontFirst']) styles += style(id, chapterPr.replace('<w:outlineLvl w:val="0"/>', '<w:outlineLvl w:val="9"/>') + (id === 'FrontFirst' ? '' : '<w:pageBreakBefore/>'), '<w:b/><w:sz w:val="32"/>');
styles += style('TableText', '<w:jc w:val="left"/><w:spacing w:before="0" w:after="0" w:line="360"/>', '<w:sz w:val="26"/>');
styles += style('TableGap', '<w:spacing w:before="0" w:after="0" w:line="40" w:lineRule="exact"/>', '<w:sz w:val="2"/>');
styles += style('TableCaption', '<w:keepNext/><w:jc w:val="center"/><w:spacing w:before="120" w:after="120" w:line="360"/>', '<w:sz w:val="26"/>');
styles += style('FigureCaption', '<w:jc w:val="center"/><w:spacing w:before="120" w:after="120" w:line="360"/>', '<w:sz w:val="26"/>');
styles += style('Code', '<w:jc w:val="left"/><w:spacing w:before="0" w:after="0" w:line="360"/><w:shd w:fill="F7F7F7"/>', '<w:sz w:val="26"/>');
styles += style('Reference', '<w:jc w:val="both"/><w:ind w:left="425" w:hanging="425"/><w:spacing w:before="0" w:after="120" w:line="360"/>', '<w:sz w:val="26"/>');
for (let n = 1; n <= 3; n++) styles += style('TOC' + n, '<w:jc w:val="left"/><w:ind w:left="' + ((n - 1) * 300) + '"/><w:spacing w:before="0" w:after="0" w:line="360"/>', '<w:sz w:val="26"/>' + (n === 1 ? '<w:b/>' : ''));
styles += style('CoverMinistry', '<w:jc w:val="center"/><w:spacing w:before="0" w:after="80" w:line="360"/>', '<w:sz w:val="26"/>');
styles += style('CoverSchool', '<w:jc w:val="center"/><w:spacing w:before="0" w:after="200" w:line="360"/>', '<w:b/><w:sz w:val="26"/>');
styles += style('CoverType', '<w:jc w:val="center"/><w:spacing w:before="140" w:after="160" w:line="360"/>', '<w:b/><w:sz w:val="30"/>');
styles += style('CoverTitle', '<w:jc w:val="center"/><w:spacing w:before="180" w:after="240" w:line="360"/>', '<w:b/><w:sz w:val="32"/>');
styles += style('CoverSubject', '<w:jc w:val="center"/><w:spacing w:before="0" w:after="180" w:line="360"/>', '<w:b/><w:sz w:val="28"/>');
styles += style('CoverMeta', '<w:jc w:val="left"/><w:spacing w:before="0" w:after="60" w:line="360"/>', '<w:b/><w:sz w:val="28"/>');
styles += style('CoverYear', '<w:jc w:val="center"/><w:spacing w:before="240" w:after="0" w:line="360"/>', '<w:b/><w:sz w:val="28"/>');
styles += '</w:styles>';
const xmlHeader = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const namespace = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"';
fs.writeFileSync(stage + '/word/document.xml', xmlHeader + '<w:document ' + namespace + '><w:body>' + body + '</w:body></w:document>');
fs.writeFileSync(stage + '/word/styles.xml', xmlHeader + styles);
fs.writeFileSync(stage + '/word/settings.xml', xmlHeader + '<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:zoom w:percent="100"/><w:defaultTabStop w:val="720"/><w:updateFields w:val="true"/><w:compat/><w:doNotAutoHyphenateCaps/></w:settings>');
for (const [file, value] of [['footerRoman.xml', 'i'], ['footerArabic.xml', '1']]) fs.writeFileSync(stage + '/word/' + file, xmlHeader + '<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:fldSimple w:instr="PAGE"><w:r><w:rPr>' + font + '<w:sz w:val="26"/></w:rPr><w:t>' + value + '</w:t></w:r></w:fldSimple></w:p></w:ftr>');
const rel = (id, type, target) => '<Relationship Id="' + id + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/' + type + '" Target="' + target + '"/>';
fs.writeFileSync(stage + '/word/_rels/document.xml.rels', xmlHeader + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + rel('rIdStyles', 'styles', 'styles.xml') + rel('rIdSettings', 'settings', 'settings.xml') + rel('rIdFooterRoman', 'footer', 'footerRoman.xml') + rel('rIdFooterArabic', 'footer', 'footerArabic.xml') + imageRels.map(r => rel(r.id, 'image', r.target)).join('') + '</Relationships>');
fs.writeFileSync(stage + '/_rels/.rels', xmlHeader + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + rel('rIdDocument', 'officeDocument', 'word/document.xml') + '</Relationships>');
fs.writeFileSync(stage + '/[Content_Types].xml', xmlHeader + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>' + [['document', 'document.main'], ['styles', 'styles'], ['settings', 'settings'], ['footerRoman', 'footer'], ['footerArabic', 'footer']].map(([f, t]) => '<Override PartName="/word/' + f + '.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.' + t + '+xml"/>').join('') + '</Types>');
fs.writeFileSync(stage + '/manifest.json', JSON.stringify({ headings: bookmarks.length, tables: tableCaptions.length, figures: figureCaptions.length, sections: 4, format: { size: 'A4', font: 'Times New Roman', points: 13, lineSpacing: 1.5, marginsCm: { top: 3, bottom: 3, left: 3.5, right: 2 } } }, null, 2));
console.log(`Prepared Word document: ${bookmarks.length} headings, ${tableCaptions.length} tables, ${figureCaptions.length} figure.`);
