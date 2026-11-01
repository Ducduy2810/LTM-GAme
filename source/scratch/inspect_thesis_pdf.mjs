import fs from 'node:fs';
import path from 'node:path';
import { createCanvas, DOMMatrix, ImageData, Path2D } from './pdf-tools/node_modules/@napi-rs/canvas/index.js';
Object.assign(globalThis, { DOMMatrix, ImageData, Path2D });
const { getDocument } = await import('./pdf-tools/node_modules/pdfjs-dist/legacy/build/pdf.mjs');
const filename = process.argv[2];
const output = process.argv[3] || 'scratch/thesis-format';
const task = getDocument({
    data: new Uint8Array(fs.readFileSync(filename)),
    useSystemFonts: true,
    wasmUrl: path.resolve('scratch/pdf-tools/node_modules/pdfjs-dist/wasm').replaceAll('\\', '/') + '/',
});
const doc = await task.promise;
let result = '';
for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const content = await page.getTextContent();
    result += `\n\n=== TRANG ${n} ===\n`;
    let lastY;
    for (const item of content.items) {
        if (!('str' in item)) continue;
        const y = item.transform[5];
        if (lastY !== undefined && Math.abs(y - lastY) > 3) result += '\n';
        result += item.str + ' ';
        lastY = y;
    }
    if (process.env.RENDER_PAGES?.split(',').includes(String(n))) {
        const viewport = page.getViewport({ scale: 1.4 });
        const canvas = createCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height));
        await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise;
        fs.writeFileSync(`${output}-page-${n}.png`, canvas.toBuffer('image/png'));
    }
}
fs.writeFileSync(`${output}.txt`, result);
console.log(`Pages: ${doc.numPages}. Extracted: ${path.resolve(output + '.txt')}`);
await task.destroy();
